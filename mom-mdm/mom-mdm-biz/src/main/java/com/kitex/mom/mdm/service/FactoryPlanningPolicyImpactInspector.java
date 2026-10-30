package com.kitex.mom.mdm.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningApsReferencesDTO;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningImpactDTO;
import com.kitex.mom.mdm.entity.MdmFactoryPlanningPolicyEntity;
import com.kitex.mom.mdm.feign.RemoteMesFactoryPlanningImpactService;
import com.kitex.mom.mdm.feign.RemotePlanFactoryPlanningImpactService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 工厂计划权威发布影响检查器。
 *
 * <p>MDM 只编排 PLAN/MES 返回的本域证据，不跨 schema 查询。任一依赖不可用或返回错工厂时，
 * 影响结果按阻断处理。</p>
 */
@Service
@RequiredArgsConstructor
public class FactoryPlanningPolicyImpactInspector {

    private final RemotePlanFactoryPlanningImpactService planImpactService;
    private final RemoteMesFactoryPlanningImpactService mesImpactService;

    private static void addPositive(List<String> blockers, Long count, String label) {
        if (count != null && count > 0) {
            blockers.add(count + label);
        }
    }

    private static MdmFactoryPlanningImpactDTO.ModuleImpact unavailable(
            String module, String factoryCode, String message) {
        return new MdmFactoryPlanningImpactDTO.ModuleImpact()
                .setModule(module).setFactoryCode(factoryCode).setAvailable(false).setMessage(message);
    }

    private static String moduleEvidence(MdmFactoryPlanningImpactDTO.ModuleImpact value) {
        return String.join(",", value(value.getTenantId()), value(value.getModule()), value(value.getFactoryCode()),
                value(value.getAvailable()), value(value.getActiveMrpCount()), value(value.getLockedApsCount()),
                value(value.getUnfinishedTaskCount()), value(value.getPendingErpEventCount()),
                value(value.getFormalSourceCount()), value(value.getIntelligentApsInstalled()));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }

    private static boolean validCount(Long count) {
        return count != null && count >= 0;
    }

    /**
     * 实时采集并生成可重复计算的影响确认号。
     */
    public MdmFactoryPlanningImpactDTO inspect(MdmFactoryPlanningPolicyEntity draft) {
        return inspect(draft, -1L);
    }

    /**
     * publishedPolicyCount包含历史发布及软删除版本；未知时不得首次启用。
     */
    public MdmFactoryPlanningImpactDTO inspect(MdmFactoryPlanningPolicyEntity draft, long publishedPolicyCount) {
        require(draft != null && draft.getId() != null, "计划协同策略草稿不存在");
        MdmFactoryPlanningImpactDTO.ModuleImpact plan = capture("PLAN", draft.getTenantId(), draft.getFactoryCode(),
                () -> planImpactService.current(draft.getFactoryCode()));
        MdmFactoryPlanningImpactDTO.ModuleImpact mes = capture("MES", draft.getTenantId(), draft.getFactoryCode(),
                () -> mesImpactService.current(draft.getFactoryCode()));
        List<String> blockers = new ArrayList<>();
        collectPlanBlockers(plan, blockers);
        collectMesBlockers(mes, blockers);
        List<String> initialBlockers = initialActivationBlockers(draft, publishedPolicyCount, plan, mes);
        String confirmationNo = sha256(String.join("|", "PLANNING_POLICY_IMPACT_V2",
                value(draft.getTenantId()), draft.getFactoryCode(), value(draft.getId()),
                value(draft.getVersionNo()), draft.getContentFingerprint(), moduleEvidence(plan), moduleEvidence(mes),
                value(publishedPolicyCount)));
        return new MdmFactoryPlanningImpactDTO()
                .setDraftId(value(draft.getId()))
                .setFactoryCode(draft.getFactoryCode())
                .setDraftVersion(draft.getVersionNo())
                .setDraftFingerprint(draft.getContentFingerprint())
                .setPublishedPolicyCount(publishedPolicyCount)
                .setInitialLocalActivationAllowed(initialBlockers.isEmpty())
                .setInitialActivationBlockers(initialBlockers)
                .setPlan(plan)
                .setMes(mes)
                .setImmediateSwitchAllowed(blockers.isEmpty())
                .setBlockers(blockers)
                .setConfirmationNo(confirmationNo)
                .setInspectedAt(LocalDateTime.now());
    }

    /**
     * Details are informative and never replace the publication impact confirmation.
     */
    public MdmFactoryPlanningApsReferencesDTO apsReferences(MdmFactoryPlanningPolicyEntity draft) {
        require(draft != null && draft.getId() != null && draft.getTenantId() != null
                && draft.getFactoryCode() != null, "策略草稿缺少组织信息");
        R<MdmFactoryPlanningApsReferencesDTO> response;
        try {
            response = planImpactService.apsReferences(draft.getFactoryCode());
        } catch (RuntimeException ex) {
            throw new CheckedException("PLAN排产占用明细暂不可用，请稍后重试");
        }
        require(response != null && response.isOk() && response.getData() != null,
                "PLAN未返回有效排产占用明细");
        var data = response.getData();
        int max = MdmFactoryPlanningApsReferencesDTO.MAX_RECORDS;
        require(Objects.equals(data.getTenantId(), draft.getTenantId())
                        && Objects.equals(data.getFactoryCode(), draft.getFactoryCode())
                        && data.getTotal() != null && data.getTotal() >= 0 && Objects.equals(data.getLimit(), max)
                        && data.getTruncated() != null && data.getObservedAt() != null && data.getRecords() != null,
                "PLAN排产占用明细组织或结构不完整");
        require(data.getRecords().size() == Math.min(data.getTotal(), max)
                && data.getTruncated() == (data.getTotal() > max), "PLAN排产占用明细总数不一致");
        var identities = new java.util.HashSet<String>();
        for (var row : data.getRecords()) {
            require(row != null && row.getSourceType() != null && java.util.Set.of("PLAN", "TASK_DETAIL", "OPERATION", "BATCH", "APS_CASE").contains(row.getSourceType())
                            && validReferenceId(row.getSourceId()) && row.getSourceDeleted() != null
                            && row.getRelationIncomplete() != null && identities.add(row.getSourceType() + ":" + row.getSourceId()),
                    "PLAN排产占用来源无效或重复");
            require((row.getPlanId() == null || validReferenceId(row.getPlanId()))
                    && (row.getTaskId() == null || validReferenceId(row.getTaskId())), "PLAN排产关联标识无效");
        }
        return data.setDraftId(draft.getId().toString());
    }

    private boolean validReferenceId(String id) {
        if (id == null || !id.matches("[1-9][0-9]{0,18}")) return false;
        try {
            return Long.parseLong(id) > 0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private List<String> initialActivationBlockers(MdmFactoryPlanningPolicyEntity draft, long publishedCount,
                                                   MdmFactoryPlanningImpactDTO.ModuleImpact plan, MdmFactoryPlanningImpactDTO.ModuleImpact mes) {
        List<String> blockers = new ArrayList<>();
        if (draft.getVersionNo() == null || draft.getVersionNo() <= 0 || publishedCount != 0) {
            blockers.add("仅从未发布过策略的工厂可首次启用");
        }
        if (!"DRAFT".equals(draft.getStatus()) || draft.getEffectiveFrom() == null
                || draft.getEffectiveFrom().isAfter(LocalDateTime.now())) {
            blockers.add("首次启用必须为立即生效的草稿");
        }
        if (!"MOM_MRP".equals(draft.getMrpAuthority())
                || !"MOM_ORDER_ONLY".equals(draft.getProductionOrderSyncMode())
                || !"MOM_APPROVAL".equals(draft.getSubstituteGovernanceMode())
                || !value(draft.getErpSystemCode()).isBlank() || !value(draft.getExternalFactoryCode()).isBlank()) {
            blockers.add("首次启用仅支持无ERP映射的独立MOM模式");
        }
        for (var evidence : List.of(plan, mes)) {
            if (!Boolean.TRUE.equals(evidence.getAvailable())) {
                blockers.add(evidence.getModule() + "首次启用证据不完整");
                continue;
            }
            addPositive(blockers, evidence.getFormalSourceCount(), "条正式合同或策略引用");
            addPositive(blockers, evidence.getLockedApsCount(), "条执行链路APS事实");
            addPositive(blockers, evidence.getPendingErpEventCount(), "条未完成下达/ERP投递");
        }
        return blockers;
    }

    private MdmFactoryPlanningImpactDTO.ModuleImpact capture(
            String module, Long tenantId, String factoryCode, RemoteCall call) {
        try {
            R<MdmFactoryPlanningImpactDTO.ModuleImpact> response = call.execute();
            if (response == null || !response.isOk() || response.getData() == null
                    || !Objects.equals(factoryCode, response.getData().getFactoryCode())) {
                return unavailable(module, factoryCode, "影响接口无有效响应或返回错工厂");
            }
            MdmFactoryPlanningImpactDTO.ModuleImpact result = response.getData();
            if (!Objects.equals(tenantId, result.getTenantId()) || !module.equals(result.getModule()) || !Boolean.TRUE.equals(result.getAvailable())
                    || !validCount(result.getActiveMrpCount()) || !validCount(result.getLockedApsCount())
                    || !validCount(result.getUnfinishedTaskCount()) || !validCount(result.getPendingErpEventCount())
                    || !validCount(result.getFormalSourceCount()) || result.getIntelligentApsInstalled() == null) {
                return unavailable(module, factoryCode, "影响证据不可用或不完整，请更新模块后重新检查");
            }
            return result;
        } catch (RuntimeException ex) {
            return unavailable(module, factoryCode, "影响接口不可用");
        }
    }

    private void collectPlanBlockers(MdmFactoryPlanningImpactDTO.ModuleImpact impact, List<String> blockers) {
        if (!Boolean.TRUE.equals(impact.getAvailable())) {
            blockers.add("PLAN影响证据不可读取");
            return;
        }
        addPositive(blockers, impact.getActiveMrpCount(), "条未结MRP");
        addPositive(blockers, impact.getLockedApsCount(), "条执行链路排产锁/案件");
        addPositive(blockers, impact.getPendingErpEventCount(), "条待完成计划下达/ERP事件");
    }

    private void collectMesBlockers(MdmFactoryPlanningImpactDTO.ModuleImpact impact, List<String> blockers) {
        if (!Boolean.TRUE.equals(impact.getAvailable())) {
            blockers.add("MES影响证据不可读取");
            return;
        }
        addPositive(blockers, impact.getUnfinishedTaskCount(), "张未完MES工单");
        addPositive(blockers, impact.getPendingErpEventCount(), "条未完成MES/ERP投递");
    }

    @FunctionalInterface
    private interface RemoteCall {
        R<MdmFactoryPlanningImpactDTO.ModuleImpact> execute();
    }
}
