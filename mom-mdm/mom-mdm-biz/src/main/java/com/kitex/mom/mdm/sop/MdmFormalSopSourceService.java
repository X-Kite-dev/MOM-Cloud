package com.kitex.mom.mdm.sop;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.mdm.dto.MdmFormalSopDTO;
import com.kitex.mom.mdm.entity.MdmSopBindingEntity;
import com.kitex.mom.mdm.entity.MdmSopOriginalEntity;
import com.kitex.mom.mdm.entity.MdmSopVersionEntity;
import com.kitex.mom.mdm.mapper.MdmSopBindingMapper;
import com.kitex.mom.mdm.mapper.MdmSopOriginalMapper;
import com.kitex.mom.mdm.mapper.MdmSopVersionMapper;
import com.kitex.mom.mdm.service.impl.MdmFormalSopServiceImpl;
import com.kitex.mom.mdm.support.MdmRouteNodeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import static com.kitex.mom.mdm.sop.MdmSopSupport.*;

/**
 * 只读正式来源。版本内容、原件字节与批准信息均核验；不以最新版替换历史精确版本。
 * 节点解析必须完整，未绑定显示缺口，已绑定但退役/撤回则拒绝新下达。
 */
@Service
@RequiredArgsConstructor
public class MdmFormalSopSourceService {
    private final MdmSopVersionMapper versions;
    private final MdmSopBindingMapper bindings;
    private final MdmSopOriginalMapper originals;
    private final MdmSopSupport support;
    private final MdmSopOriginalStorage storage;
    private final MdmRouteNodeResolver nodes;

    public Page<MdmFormalSopDTO.Source> search(MdmFormalSopDTO.SearchQuery query) {
        require(query != null, "正式SOP查询不能为空");
        support.sourceScope(query.getFactoryCode());
        require(query.getCurrent() > 0 && query.getSize() > 0 && query.getSize() <= 50, "正式SOP查询每页1至50条");
        var wrapper = versionScope().eq(MdmSopVersionEntity::getStatus, "RELEASED");
        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            String keyword = text(query.getKeyword(), 100, "关键字", false);
            wrapper.and(w -> w.like(MdmSopVersionEntity::getSopNo, keyword).or().like(MdmSopVersionEntity::getName, keyword));
        }
        var page = versions.selectPage(new Page<>(query.getCurrent(), query.getSize()),
                wrapper.orderByAsc(MdmSopVersionEntity::getSopNo).orderByDesc(MdmSopVersionEntity::getVersionNo));
        var result = new Page<MdmFormalSopDTO.Source>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream().map(this::project).toList());
        return result;
    }

    public MdmFormalSopDTO.Verification verify(MdmFormalSopDTO.VerifyQuery query) {
        require(query != null, "正式SOP核验不能为空");
        support.sourceScope(query.getFactoryCode());
        require(query.getContentHash() != null && query.getContentHash().matches("[0-9a-f]{64}"), "必须提供原冻结版本内容指纹");
        var version = exact(id(query.getVersionId(), "正式SOP版本ID"));
        validateFormal(version, true);
        boolean matches = Objects.equals(query.getContentHash(), version.getContentHash());
        return new MdmFormalSopDTO.Verification().setMatched(matches).setSource(project(version))
                .setMessage(!matches ? "与原冻结内容指纹不一致，不能替换为当前内容"
                        : "WITHDRAWN".equals(version.getStatus()) ? "原版本已安全撤回，禁止新的开工/继续执行"
                        : "RETIRED".equals(version.getStatus()) ? "原版本已退役：禁止新下达，原冻结版仍可按MES规则执行"
                        : "精确版本、批准证明与原件字节核验一致");
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MdmFormalSopDTO.Manifest resolve(MdmFormalSopDTO.ResolveQuery query) {
        require(query != null, "正式SOP下达解析不能为空");
        String factory = support.sourceScope(query.getFactoryCode());
        require(query.getNodes() != null && !query.getNodes().isEmpty() && query.getNodes().size() <= 200,
                "单次正式SOP下达解析须指定1至200个节点，不能静默截断");
        Set<String> unique = new HashSet<>();
        List<MdmFormalSopDTO.NodeSources> result = new ArrayList<>();
        int totalBindings = 0;
        List<MdmFormalSopDTO.Node> ordered = new ArrayList<>(query.getNodes());
        for (var node : ordered) {
            require(node != null && node.getRouteCode() != null && node.getRouteNodeCode() != null
                            && !node.getRouteNodeCode().isBlank(), "正式SOP解析须明确稳定路线节点");
            require(unique.add(node.getRouteCode() + "\0" + node.getRouteNodeCode()), "正式SOP解析节点不能重复");
        }
        ordered.sort(Comparator.comparing(MdmFormalSopDTO.Node::getRouteCode)
                .thenComparing(MdmFormalSopDTO.Node::getRouteNodeCode));
        for (var node : ordered) {
            var actual = nodes.resolve(node.getRouteCode(), node.getRouteNodeCode(), node.getProcessCode(), false);
            var scoped = bindings.selectList(new LambdaQueryWrapper<MdmSopBindingEntity>()
                    .eq(MdmSopBindingEntity::getTenantId, support.tenant()).eq(MdmSopBindingEntity::getFactoryCode, factory)
                    .eq(MdmSopBindingEntity::getRouteCode, actual.getRouteCode())
                    .eq(MdmSopBindingEntity::getRouteNodeCode, actual.getRouteNodeCode())
                    .eq(MdmSopBindingEntity::getStatus, "BOUND").orderByAsc(MdmSopBindingEntity::getSopNo)
                    .last("LIMIT 1001"));
            totalBindings += scoped.size();
            require(totalBindings <= 1000, "正式SOP绑定超过单次1000条上限，请拆分真实业务批次，不允许截断冻结");
            var item = new MdmFormalSopDTO.NodeSources().setNode(new MdmFormalSopDTO.Node()
                    .setRouteCode(actual.getRouteCode()).setRouteNodeCode(actual.getRouteNodeCode())
                    .setProcessCode(actual.getProcessCode()));
            for (var binding : scoped) {
                require(Objects.equals(actual.getProcessCode(), binding.getProcessCode()), "SOP绑定主工序归属已变化，须受控重新绑定");
                var version = exact(binding.getVersionId());
                validateFormal(version, true);
                require("RELEASED".equals(version.getStatus())
                                && Objects.equals(binding.getVersionHash(), version.getContentHash()),
                        "节点" + actual.getRouteNodeCode() + "绑定的SOP " + version.getSopNo()
                                + "已退役、撤回或指纹变化，请先修正正式绑定再下达");
                item.getSources().add(project(version));
            }
            if (scoped.isEmpty()) item.getGaps().add("NO_FORMAL_SOP_BINDING：该节点未声明正式SOP，旧附件仍是LEGACY_REFERENCE");
            result.add(item);
        }
        return new MdmFormalSopDTO.Manifest().setTenantId(support.tenant().toString()).setFactoryCode(factory)
                .setNodes(result).setComplete(true)
                .setManifestHash(hash("FORMAL_SOP_MANIFEST_V1", support.tenant(), factory, result));
    }

    /** 下载精确正式版原件；调用方菜单/业务权限仍由控制器负责。 */
    public OriginalContent download(String originalId, String versionId, String contentHash) {
        support.access(false);
        var original = original(id(originalId, "原件ID"));
        if (versionId != null && !versionId.isBlank()) {
            var version = exact(id(versionId, "SOP版本ID"));
            require(Objects.equals(version.getOriginalId(), original.getId())
                    && Objects.equals(version.getContentHash(), contentHash), "原件与所读版本/指纹不一致");
        } else {
            // 无版本的原件仅允许原上传人预览，不可枚举其他编制人的未提交原稿。
            require(Objects.equals(original.getUploadedBy(), support.actor()), "未绑定版本的原件仅原上传人可查看");
        }
        return new OriginalContent(original.getFileName(), original.getMediaType(), storage.readVerified(original));
    }

    public record OriginalContent(String fileName, String mediaType, byte[] bytes) { }

    private void validateFormal(MdmSopVersionEntity version, boolean verifyBytes) {
        require(Set.of("RELEASED", "RETIRED", "WITHDRAWN").contains(version.getStatus())
                        && version.getReviewedBy() != null && version.getReviewedTime() != null
                        && version.getEffectiveFrom() != null
                        && !version.getEffectiveFrom().isAfter(LocalDateTime.now())
                        && Objects.equals(version.getContentHash(), MdmFormalSopServiceImpl.contentHash(version)),
                "该SOP没有完整正式批准证明或内容已损坏");
        var original = original(version.getOriginalId());
        require(Objects.equals(original.getSha256(), version.getOriginalHash())
                        && Objects.equals(original.getByteSize(), version.getOriginalSize())
                        && Objects.equals(original.getMediaType(), version.getMediaType()), "SOP版本与原件证明不一致");
        if (verifyBytes) storage.readVerified(original);
    }

    private MdmFormalSopDTO.Source project(MdmSopVersionEntity version) {
        return new MdmFormalSopDTO.Source().setTenantId(version.getTenantId().toString()).setFactoryCode(version.getFactoryCode())
                .setVersionId(version.getId().toString()).setSopNo(version.getSopNo()).setName(version.getName())
                .setVersionNo(version.getVersionNo()).setStatus(version.getStatus()).setContentHash(version.getContentHash())
                .setOriginalId(version.getOriginalId().toString()).setOriginalHash(version.getOriginalHash())
                .setOriginalSize(version.getOriginalSize().toString()).setMediaType(version.getMediaType())
                .setDescription(version.getDescription()).setApplicability(version.getApplicability())
                .setApprovedBy(version.getReviewedBy()).setApprovedAt(version.getReviewedTime()).setEffectiveFrom(version.getEffectiveFrom())
                .setWithdrawalReason(version.getRetireReason()).setIssueAllowed("RELEASED".equals(version.getStatus()))
                .setFrozenStartAllowed(Set.of("RELEASED", "RETIRED").contains(version.getStatus()))
                .setOriginalDownloadPath("/mdm/mdmSop/originals/" + version.getOriginalId()
                        + "?versionId=" + version.getId() + "&contentHash=" + version.getContentHash());
    }

    private MdmSopVersionEntity exact(Long versionId) {
        var version = versions.selectOne(versionScope().eq(MdmSopVersionEntity::getId, versionId));
        require(version != null, "精确SOP版本不存在或不可访问");
        return version;
    }

    private MdmSopOriginalEntity original(Long originalId) {
        var original = originals.selectOne(new LambdaQueryWrapper<MdmSopOriginalEntity>()
                .eq(MdmSopOriginalEntity::getTenantId, support.tenant())
                .eq(MdmSopOriginalEntity::getFactoryCode, support.access(false))
                .eq(MdmSopOriginalEntity::getId, originalId));
        require(original != null, "精确SOP原件不存在或不可访问");
        return original;
    }

    private LambdaQueryWrapper<MdmSopVersionEntity> versionScope() {
        return new LambdaQueryWrapper<MdmSopVersionEntity>().eq(MdmSopVersionEntity::getTenantId, support.tenant())
                .eq(MdmSopVersionEntity::getFactoryCode, support.access(false));
    }
}
