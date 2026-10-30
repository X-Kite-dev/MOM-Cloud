package com.kitex.mom.qms.dto;

/** 客户端只能指定期望原件及修订目标，身份/组织/原件均由服务端冻结。 */
public record QmsInspectionRevisionApply(
        Long taskId,
        Long baseEventId,
        String baseFingerprint,
        String result,
        Integer usable,
        Integer handled,
        String reason) {}
