package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 智能 APS 案件命令集合。
 *
 * <p>命令只携带业务身份、期望版本和永久请求号；名称、状态、数量和工厂均由服务端重采。</p>
 */
public final class PlnApsCaseCommand {
    private PlnApsCaseCommand() {
    }

    /**
     * 创建或幂等复用排产案件。
     */
    @Data
    public static class Launch {
        @Schema(description = "案件类型，使用PlnApsCaseTypeEnum代码")
        private String caseType;
        @Schema(description = "来源类型：OMS_ORDER、MPS_VERSION、MRP_LINE或PRODUCTION_PLAN")
        private String sourceType;
        @Schema(description = "来源雪花主键，使用字符串避免浏览器精度丢失")
        private String sourceId;
        @Schema(description = "调用方已看到的来源业务版本；服务端必须重采并完全匹配")
        private String sourceVersion;
        @Schema(description = "永久幂等请求号；同号异内容拒绝")
        private String requestId;
    }

    /**
     * 案件中心分页条件。
     */
    @Data
    public static class Query {
        @Schema(description = "案件业务分组：PREPARE、DECISION、EXECUTION或CLOSED")
        private String caseGroup;
        private String caseNo;
        private String caseType;
        private String caseStatus;
        private String currentStage;
        private String sourceType;
        private String sourceCode;
    }

    /**
     * 通过显式领域动作推进案件，不接受任意目标状态。
     */
    @Data
    public static class Advance {
        @Schema(description = "受控动作代码")
        private String action;
        @Schema(description = "永久幂等请求号")
        private String requestId;
        @Schema(description = "调用方期望的案件乐观锁版本")
        private Integer expectedVersion;
        @Schema(description = "调用方期望的案件聚合上下文SHA-256")
        private String expectedContextFingerprint;
        @Schema(description = "本次推进的业务原因或依据")
        private String reason;
    }

    /**
     * 取消未形成正式发布结果的案件。
     */
    @Data
    public static class Cancel {
        @Schema(description = "永久幂等请求号")
        private String requestId;
        @Schema(description = "调用方期望的案件乐观锁版本")
        private Integer expectedVersion;
        @Schema(description = "调用方期望的案件聚合上下文SHA-256")
        private String expectedContextFingerprint;
        @Schema(description = "取消原因")
        private String reason;
    }
}
