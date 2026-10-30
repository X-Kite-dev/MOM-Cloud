package com.kitex.mom.plan.service.aps.collector;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 当前租户工厂内一次 APS 资源取证的不可变范围。
 */
public record PlnApsResourceCollectionContext(
        Long tenantId,
        String factoryCode,
        Long caseId,
        String caseFingerprint,
        LocalDateTime horizonStart,
        LocalDateTime horizonEnd,
        List<SourceRef> sources,
        List<String> productCodes,
        List<String> planNos,
        List<String> workOrderCodes,
        String planningPolicyId,
        Integer planningPolicyVersion,
        String planningPolicyFingerprint,
        String mrpAuthority,
        String formalPlanContractId,
        String formalPlanContractFingerprint) {

    /**
     * 服务端已核验的案件来源，不接收浏览器自报编码。
     */
    public record SourceRef(String type, Long id, String code, String version, String fingerprint) {
    }
}
