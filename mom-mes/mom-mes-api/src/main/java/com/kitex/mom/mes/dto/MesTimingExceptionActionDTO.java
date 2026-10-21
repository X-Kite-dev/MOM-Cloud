package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 原动作六文本字段；同号不得改正文，非SUBMIT的proposedUntil必须为空字符串。
 */
@Schema(description = "超时例外受控动作")
public record MesTimingExceptionActionDTO(
        @Schema(description = "永久动作原号") String requestId,
        @Schema(description = "原超时观察ID") String caseId,
        @Schema(description = "页面所见版本整数字符串") String expectedVersion,
        @Schema(description = "SUBMIT/WITHDRAW/APPROVE/REJECT/REVOKE") String action,
        @Schema(description = "现场申请或审核原因") String reason,
        @Schema(description = "申请截止，本地ISO秒；其他动作空字符串") String proposedUntil) {
}

