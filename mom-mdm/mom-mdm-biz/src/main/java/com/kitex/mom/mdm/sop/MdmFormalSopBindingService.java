package com.kitex.mom.mdm.sop;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.mdm.dto.MdmFormalSopDTO;
import com.kitex.mom.mdm.entity.MdmSopBindingEntity;
import com.kitex.mom.mdm.entity.MdmSopVersionEntity;
import com.kitex.mom.mdm.mapper.MdmSopBindingMapper;
import com.kitex.mom.mdm.mapper.MdmSopVersionMapper;
import com.kitex.mom.mdm.support.MdmRouteNodeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;
import java.util.Set;
import static com.kitex.mom.mdm.sop.MdmSopSupport.*;

/** 同一节点/SOP版本族受控替换，明确解绑，不触碰旧附件或其他节点。 */
@Service
@RequiredArgsConstructor
public class MdmFormalSopBindingService {
    private final MdmSopBindingMapper bindings;
    private final MdmSopVersionMapper versions;
    private final MdmSopSupport support;
    private final MdmSopJournal journal;
    private final MdmRouteNodeResolver resolver;
    private final MdmFormalSopSourceService sources;

    public Page<MdmSopBindingEntity> page(String routeCode, String routeNodeCode, String versionId, long current, long size) {
        support.access(false);
        require(current > 0 && size > 0 && size <= 50, "绑定分页每页1至50条");
        var query = scope();
        if (versionId != null && !versionId.isBlank()) {
            query.eq(MdmSopBindingEntity::getVersionId, version(id(versionId, "SOP版本ID")).getId());
        }
        return bindings.selectPage(new Page<>(current, size), query
                .eq(routeCode != null && !routeCode.isBlank(), MdmSopBindingEntity::getRouteCode, routeCode)
                .eq(routeNodeCode != null && !routeNodeCode.isBlank(), MdmSopBindingEntity::getRouteNodeCode, routeNodeCode)
                .orderByAsc(MdmSopBindingEntity::getRouteCode).orderByAsc(MdmSopBindingEntity::getRouteNodeCode)
                .orderByAsc(MdmSopBindingEntity::getSopNo));
    }

    @PreAuthorize("@pms.hasPermission('mdm_sop_bind')")
    @Transactional(rollbackFor = Exception.class)
    public MdmFormalSopDTO.Receipt execute(MdmFormalSopDTO.BindCommand command) {
        String factory = support.access(true);
        require(command != null && command.getAction() != null && Set.of("BIND", "UNBIND").contains(command.getAction()), "正式SOP绑定动作无效");
        require(command.getRouteNodeCode() != null && !command.getRouteNodeCode().isBlank(), "必须选择稳定路线节点，不能只按主工序绑定");
        text(command.getReason(), 1000, "绑定变更原因", true);
        var attempt = journal.begin(command.getRequestId(), command.getAction(), command);
        if (attempt.previous() != null) return attempt.previous();
        Long versionId = id(command.getVersionId(), "SOP版本ID");
        var version = version(versionId);
        journal.lock("FAMILY:" + version.getSopNo());
        version = version(versionId);
        var node = resolver.resolve(command.getRouteCode(), command.getRouteNodeCode(), command.getProcessCode(), true);
        var existing = bindings.selectOne(scope().eq(MdmSopBindingEntity::getRouteCode, node.getRouteCode())
                .eq(MdmSopBindingEntity::getRouteNodeCode, node.getRouteNodeCode())
                .eq(MdmSopBindingEntity::getSopNo, version.getSopNo()).last("FOR UPDATE"));
        require(Objects.equals(command.getExpectedHash(), existing == null ? "ABSENT" : existing.getBindingHash()),
                "该节点正式SOP绑定已变化，请刷新原绑定再确认替换");
        String before = existing == null ? null : JSONUtil.toJsonStr(existing);
        boolean binding = "BIND".equals(command.getAction());
        if (binding) {
            var verification = sources.verify(new MdmFormalSopDTO.VerifyQuery().setFactoryCode(factory)
                    .setVersionId(versionId.toString()).setContentHash(version.getContentHash()));
            require(verification.isMatched() && verification.getSource().isIssueAllowed(), "只能绑定可新下达的已发布SOP精确版本");
            require(existing == null || !"BOUND".equals(existing.getStatus()) || !Objects.equals(existing.getVersionId(), versionId),
                    "该节点已绑定同一SOP版本，无需重复绑定");
        } else {
            require(existing != null && "BOUND".equals(existing.getStatus()) && Objects.equals(existing.getVersionId(), versionId),
                    "只能解绑当前读到的精确版本，不允许跨版本解绑");
        }
        var target = existing == null ? new MdmSopBindingEntity() : existing;
        target.setTenantId(support.tenant());
        target.setFactoryCode(factory);
        target.setRouteCode(node.getRouteCode());
        target.setRouteNodeCode(node.getRouteNodeCode());
        target.setProcessCode(node.getProcessCode());
        target.setSopNo(version.getSopNo());
        target.setVersionId(versionId);
        target.setVersionHash(version.getContentHash());
        target.setStatus(binding ? "BOUND" : "UNBOUND");
        target.setBindingHash(hash("FORMAL_SOP_BINDING_V1", support.tenant(), factory, target.getRouteCode(),
                target.getRouteNodeCode(), target.getProcessCode(), target.getSopNo(), versionId,
                target.getVersionHash(), target.getStatus(), attempt.commandHash()));
        require((existing == null ? bindings.insert(target) : bindings.updateById(target)) == 1, "SOP节点绑定保存失败");
        return journal.finish(attempt, versionId, target.getId().toString(), target.getStatus(), target.getBindingHash(), before);
    }

    private MdmSopVersionEntity version(Long id) {
        var version = versions.selectOne(new LambdaQueryWrapper<MdmSopVersionEntity>()
                .eq(MdmSopVersionEntity::getTenantId, support.tenant())
                .eq(MdmSopVersionEntity::getFactoryCode, support.access(false)).eq(MdmSopVersionEntity::getId, id));
        require(version != null, "SOP版本不属于当前授权工厂");
        return version;
    }

    private LambdaQueryWrapper<MdmSopBindingEntity> scope() {
        return new LambdaQueryWrapper<MdmSopBindingEntity>().eq(MdmSopBindingEntity::getTenantId, support.tenant())
                .eq(MdmSopBindingEntity::getFactoryCode, support.access(false));
    }
}
