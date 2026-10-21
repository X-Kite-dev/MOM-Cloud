package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 不良报工时间证据查询行。只读取原NG报告，不从操作流水猜来源；
 * 长整数、数量和时刻均按文本交付，sourceUnqualifiedReportId不属于GOOD报告ID域。
 */
@Schema(description = "不良报工原事实与时间证据")
public record MesUnqualifiedTimingRecordDTO(
        @Schema(description = "不良报告ID文本") String sourceUnqualifiedReportId,
        @Schema(description = "原报工请求号，旧历史可能为空") String requestId,
        @Schema(description = "实际租户ID文本") String tenantId,
        @Schema(description = "实际工厂") String factoryCode,
        @Schema(description = "生产工单") String taskCode,
        @Schema(description = "派工单") String arrangeCode,
        @Schema(description = "工序任务") String produceTaskCode,
        @Schema(description = "物料编码") String materialCode,
        @Schema(description = "物料原名称") String materialName,
        @Schema(description = "生产标识；新双标识协议下为规范码，旧行保留原值") String identifier,
        @Schema(description = "原不良数量文本") String quantity,
        @Schema(description = "原计量单位，历史未冻结为空") String unit,
        @Schema(description = "原不良项") String unqualifiedType,
        @Schema(description = "原操作者") String workerName,
        @Schema(description = "原报告时间") String reportTime,
        @Schema(description = "服务器接受时间，历史无记录为空") String timingAcceptedAt,
        @Schema(description = "原时间证据JSON，不用当前规则重算") String timingEvidenceJson,
        @Schema(description = "原始扫描标识，历史未留存为空") String submittedIdentifier,
        @Schema(description = "双标识证据版本，历史为空") String identificationVersion,
        @Schema(description = "原双标识完整证据") String identificationSnapshot,
        @Schema(description = "原双标识SHA-256") String identificationFingerprint) {
}
