package com.kitex.mom.mes.agent.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mes.agent.dto.MesFormalSopEvidenceDTO;
import com.kitex.mom.mes.dto.MdmProcessRouteWorkingDTO;
import com.kitex.mom.mes.dto.MesFormalSopNodeDTO;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import com.kitex.mom.mes.service.impl.MesSopFreezeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 当前授权工单的原冻结正式SOP投影；完整解析、精确节点，不查询现行绑定补历史。
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("@pms.hasPermission('ai:knowledge:view') && @pms.hasPermission('mesTask:show')")
public class MesFormalSopEvidenceService {
    private final MesTaskMapper tasks;
    private final RemoteMdmFactoryAccessService factories;
    private final MesSopFreezeService freeze;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MesFormalSopEvidenceDTO.Evidence evidence(MesFormalSopEvidenceDTO.Query query) {
        var user = SecurityUtils.getUser();
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        require(query != null && user != null && user.getId() != null && tenant != null && tenant > 0
                && Objects.equals(user.getTenantId(), tenant) && factory != null && !factory.isBlank()
                && Objects.equals(query.factoryCode(), factory) && !TenantContextHolder.getTenantSkip()
                && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()), "正式SOP知识须实名同租户当前工厂，不能跳过隔离");
        require(query.taskCode() != null && !query.taskCode().isBlank() && query.taskCode().length() <= 100, "须提供精确工单号");
        var access = factories.check(new MdmFactoryAccessCheck().setUserId(user.getId()).setFactoryCode(factory).setRequiredPermission("read"));
        require(access != null && access.isOk() && Boolean.TRUE.equals(access.getData()), "当前工厂实际READ不可核验");
        var rows = tasks.selectList(new LambdaQueryWrapper<MesTaskEntity>().eq(MesTaskEntity::getTenantId, tenant)
                .eq(MesTaskEntity::getFactoryCode, factory).eq(MesTaskEntity::getCode, query.taskCode().trim()).last("LIMIT 2"));
        require(rows.size() == 1, "工单不属于当前授权工厂或工单号不唯一");
        var task = rows.get(0);
        require(!"CREATE".equals(task.getStatus()), "草稿工单尚未正式下达，不能冒充原冻结制造依据");
        String snapshot = task.getProcessRouteSnapshot();
        require(snapshot != null && snapshot.getBytes(StandardCharsets.UTF_8).length <= 2 * 1024 * 1024,
                "工单原路线不存在或超过2MiB，不允许截断正式SOP证据");
        List<MdmProcessRouteWorkingDTO> route;
        try {
            route = JSONUtil.toList(snapshot, MdmProcessRouteWorkingDTO.class);
        } catch (Exception failure) {
            throw new CheckedException("工单原路线快照损坏，不能建立正式SOP关系");
        }
        require(route != null && !route.isEmpty() && route.size() <= 500, "原路线节点超出1至500项完整证据预算");
        List<MesFormalSopNodeDTO> formal = new ArrayList<>();
        List<String> legacy = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        Set<String> nodes = new HashSet<>();
        int count = 0;
        for (var node : route) {
            require(node != null, "原路线包含空节点");
            if (node.getFormalSops() == null) {
                legacy.add(node.getRouteNodeCode() == null ? "LEGACY_PROCESS:" + Objects.toString(node.getProcessCode(), node.getRouteCode()) : node.getRouteNodeCode());
                continue;
            }
            require(node.getRouteNodeCode() != null && nodes.add(node.getRouteNodeCode()), "原正式SOP节点身份缺失或重复");
            String process = node.getProcessCode() == null || node.getProcessCode().isBlank() ? node.getRouteCode() : node.getProcessCode();
            freeze.validateFrozen(node.getFormalSops(), tenant.toString(), factory, task.getProcessRouteCode(), node.getRouteNodeCode(), process);
            count += node.getFormalSops().getSources().size();
            require(count <= 1000, "原正式SOP来源超出1000项，不允许部分关系冒充完整图谱");
            formal.add(node.getFormalSops());
        }
        if (!legacy.isEmpty()) gaps.add("部分或全部原节点只有旧附件引用，没有正式SOP批准/字节证明；禁止回填当前MDM版本");
        gaps.add("原冻结来源不是当前撤回结论；每次应用须对原versionId/contentHash调用MDM固定verify并重核当前来源阅读权");
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(snapshot.getBytes(StandardCharsets.UTF_8)));
            return new MesFormalSopEvidenceDTO.Evidence("MES_FORMAL_SOP_EVIDENCE_V1", tenant.toString(), factory,
                    task.getId().toString(), task.getCode(), hash, formal, legacy, gaps, true, true);
        } catch (Exception failure) {
            throw new IllegalStateException("工单原正式SOP证据摘要不可生成", failure);
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
