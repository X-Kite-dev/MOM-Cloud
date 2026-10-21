package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 已排产/已下达生产计划的受控变更命令。
 *
 * <p>该流程不是直接改写计划行，而是先冻结PLAN、APS、MRP及MES事实，经异岗复核后依次撤回未执行事实，
 * 再按目标数量和交期重算MRP。新的计划必须重新排产、锁定和下达，避免旧资源占用被静默复用。</p>
 */
public final class PlnIssueChangeDTO {

    private PlnIssueChangeDTO() {
    }

    @Data
    @Schema(description = "生产计划受控变更申请")
    public static class SubmitCommand {
        /** 待变更生产计划主键。 */
        private Long issueId;
        /** 客户端生成并在不确定结果重试时复用的永久请求号。 */
        private String requestId;
        /** 变更后的计划数量。 */
        private BigDecimal targetQuantity;
        /** 变更后的需求交期。 */
        private LocalDate targetDemandDate;
        /** 可审计的业务变更原因。 */
        private String reason;
    }

    @Data
    @Schema(description = "生产计划受控变更复核")
    public static class ReviewCommand {
        /** 生产计划变更单主键。 */
        private Long changeId;
        /** 客户端生成并在不确定结果重试时复用的永久请求号。 */
        private String requestId;
        /** true 批准并立即执行，false 驳回。 */
        private Boolean approved;
        /** 复核人页面看到的申请证据指纹，用于防止旧页面审批。 */
        private String expectedEvidenceFingerprint;
        /** 复核依据或驳回原因。 */
        private String reason;
    }

    @Data
    @Schema(description = "生产计划受控变更失败续跑")
    public static class RetryCommand {
        /** 失败或部分完成的生产计划变更单主键。 */
        private Long changeId;
        /** 本次人工续跑的永久请求号。 */
        private String requestId;
        /** 续跑原因及现场确认说明。 */
        private String reason;
    }

    @Data
    @Schema(description = "申请人撤回待复核生产计划变更")
    public static class WithdrawCommand {
        /** 待复核生产计划变更单主键。 */
        private Long changeId;
        /** 本次撤回命令的永久请求号。 */
        private String requestId;
        /** 撤回原因。 */
        private String reason;
    }
}
