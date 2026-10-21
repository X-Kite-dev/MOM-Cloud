package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Map;

/** 既有专用局部重排与统一智能 APS 案件之间的命令和归一化视图。 */
public final class PlnApsReplanDTO {

    private PlnApsReplanDTO() {
    }

    @Data
    @Schema(description = "从既有局部重排事实启动统一APS案件")
    public static class LaunchCommand {
        @Schema(description = "SHORTAGE、RESOURCE、QUALITY、INSERTION或EXECUTION_DEVIATION")
        private String sourceType;
        @Schema(description = "专用局部重排案件主键，使用文本避免前端精度丢失")
        private String sourceId;
        @Schema(description = "客户端生成的永久幂等请求号")
        private String requestId;
    }

    @Data
    @Schema(description = "从排程效果回评启动执行偏差案件")
    public static class EffectLaunchCommand {
        @Schema(description = "客户端生成的永久幂等请求号")
        private String requestId;
        @Schema(description = "用户确认弹框中看到的偏差行动主键；使用文本避免前端精度丢失")
        private String expectedActionId;
        @Schema(description = "用户确认弹框中看到的MES事实指纹")
        private String expectedEffectFingerprint;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "统一APS工作台中的局部重排投影")
    public static class Projection {
        private PlnApsCaseDTO apsCase;
        private String sourceType;
        private String sourceId;
        private String sourceCode;
        private String sourceStatus;
        private String issueId;
        private String issueNo;
        private String scenarioId;
        private String originalTaskId;
        private String proposalTaskId;
        private String evidenceFingerprint;
        private Boolean movableScopeVerified;
        private String summary;
        private Map<String, Object> metrics;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "效果偏差转统一APS案件的判定结果")
    public static class EffectCaseResult {
        private Boolean triggered;
        private String reason;
        private Integer toleranceMinutes;
        private String effectReviewId;
        private PlnApsCaseDTO apsCase;
    }
}
