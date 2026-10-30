package com.kitex.mom.plan.service.aps.collector;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 单一业务模块返回的 APS 证据包。
 *
 * <p>状态只能是 COMPLETE、PARTIAL 或 UNAVAILABLE。采集失败必须携带缺口，禁止返回空集合冒充成功。</p>
 */
public record PlnApsResourceEvidence(
        String module,
        String status,
        boolean critical,
        String sourceVersion,
        LocalDateTime observedAt,
        Map<String, Object> summary,
        List<String> evidenceRefs,
        List<Gap> gaps,
        String contentFingerprint) {

    /**
     * 结构化数据缺口及其原业务处理入口。
     */
    public record Gap(String code, String category, String severity, String sourceType,
                      String sourceCode, String title, String description,
                      String resolutionRoute, Map<String, Object> resolutionParams) {
    }
}
