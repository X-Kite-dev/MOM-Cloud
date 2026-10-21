package com.kitex.mom.mdm.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
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

    /** 实时采集并生成可重复计算的影响确认号。 */
    public MdmFactoryPlanningImpactDTO inspect(MdmFactoryPlanningPolicyEntity draft) {
        require(draft != null && draft.getId() != null, "计划协同策略草稿不存在");
        MdmFactoryPlanningImpactDTO.ModuleImpact plan = capture("PLAN", draft.getFactoryCode(),
                () -> planImpactService.current(draft.getFactoryCode()));
        MdmFactoryPlanningImpactDTO.ModuleImpact mes = capture("MES", draft.getFactoryCode(),
                () -> mesImpactService.current(draft.getFactoryCode()));
        List<String> blockers = new ArrayList<>();
        collectPlanBlockers(plan, blockers);
        collectMesBlockers(mes, blockers);
        String confirmationNo = sha256(String.join("|", "PLANNING_POLICY_IMPACT_V1",
                value(draft.getTenantId()), draft.getFactoryCode(), value(draft.getId()),
                value(draft.getVersionNo()), draft.getContentFingerprint(), moduleEvidence(plan), moduleEvidence(mes)));
        return new MdmFactoryPlanningImpactDTO()
                .setDraftId(value(draft.getId()))
                .setFactoryCode(draft.getFactoryCode())
                .setDraftVersion(draft.getVersionNo())
                .setDraftFingerprint(draft.getContentFingerprint())
                .setPlan(plan)
                .setMes(mes)
                .setImmediateSwitchAllowed(blockers.isEmpty())
                .setBlockers(blockers)
                .setConfirmationNo(confirmationNo)
                .setInspectedAt(LocalDateTime.now());
    }

    private MdmFactoryPlanningImpactDTO.ModuleImpact capture(
            String module, String factoryCode, RemoteCall call) {
        try {
            R<MdmFactoryPlanningImpactDTO.ModuleImpact> response = call.execute();
            if (response == null || !response.isOk() || response.getData() == null
                    || !Objects.equals(factoryCode, response.getData().getFactoryCode())) {
                return unavailable(module, factoryCode, "影响接口无有效响应或返回错工厂");
            }
            MdmFactoryPlanningImpactDTO.ModuleImpact result = response.getData();
            result.setModule(module).setAvailable(Boolean.TRUE);
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
        addPositive(blockers, impact.getLockedApsCount(), "个已进入执行链路的APS案件");
        addPositive(blockers, impact.getPendingErpEventCount(), "条待完成计划下达/ERP事件");
    }

    private void collectMesBlockers(MdmFactoryPlanningImpactDTO.ModuleImpact impact, List<String> blockers) {
        if (!Boolean.TRUE.equals(impact.getAvailable())) {
            blockers.add("MES影响证据不可读取");
            return;
        }
        addPositive(blockers, impact.getUnfinishedTaskCount(), "张未完MES工单");
    }

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
        return String.join(",", value(value.getModule()), value(value.getFactoryCode()),
                value(value.getAvailable()), value(value.getActiveMrpCount()), value(value.getLockedApsCount()),
                value(value.getUnfinishedTaskCount()), value(value.getPendingErpEventCount()));
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

    @FunctionalInterface
    private interface RemoteCall {
        R<MdmFactoryPlanningImpactDTO.ModuleImpact> execute();
    }
}
