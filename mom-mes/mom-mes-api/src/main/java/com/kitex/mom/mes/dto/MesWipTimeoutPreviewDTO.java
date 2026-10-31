package com.kitex.mom.mes.dto;

import lombok.Data;

import java.util.List;

/** 试算仅接受草稿匹配字段，不接受调用方提供观察时间、组织或库存事实。 */
public final class MesWipTimeoutPreviewDTO {
    private MesWipTimeoutPreviewDTO() {}

    @Data
    public static class Query {
        private String stockId;
        private String draftId;
        private String name;
        private String lineCode;
        private String routeCode;
        private String productCode;
        private String timeoutSec;
        private String level2Sec;
        private String level3Sec;
        private Boolean enabled;

        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("试算不接受额外范围或替换来源字段");
        }
    }

    public record Source(
            String stockId,
            String barcode,
            String productCode,
            String productName,
            String quantity,
            String unit,
            String lineCode,
            String routeCode,
            String arrangeCode,
            String inTime) {}

    public record Sources(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            String barcode,
            String afterId,
            List<Source> rows,
            boolean truncated,
            String nextAfterId) {}

    public record Draft(
            String draftId,
            String name,
            String lineCode,
            String routeCode,
            String productCode,
            String timeoutSec,
            String level2Sec,
            String level3Sec,
            boolean enabled) {}

    public record Rule(
            String code,
            String name,
            boolean draft,
            String lineCode,
            String routeCode,
            String productCode,
            int specificity,
            String timeoutSec,
            String level2Sec,
            String level3Sec) {}

    public record Preview(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            String observedAt,
            String zoneId,
            Source source,
            Draft draft,
            String elapsedSeconds,
            String sourceTimeStatus,
            String draftStatus,
            List<Rule> matches,
            Rule selectedRule,
            String alertLevel,
            boolean wouldAlert) {}
}
