package com.kitex.mom.pms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * OMS订单变更衔接PMS采购订单行级撤回的内部契约。
 *
 * <p>契约只接受PLAN已经保存的采购订单和订单行主键，不允许按物料编码或单号猜测关联；
 * 提交后仍由PMS采购角色异岗复核，OMS只能查询永久回执，不能代替采购审批。</p>
 */
public final class PmsOmsSupplyWithdrawalDTO {
    private PmsOmsSupplyWithdrawalDTO() {
    }

    /**
     * 精确目标行预览命令。
     */
    @Data
    @Accessors(chain = true)
    public static class PreviewCommand {
        private Long pmsOrderId;
        private List<Long> orderDetailIds;
    }

    /**
     * 由OMS级联案件幂等创建采购撤回申请的命令。
     */
    @Data
    @Accessors(chain = true)
    public static class SubmitCommand extends PreviewCommand {
        private String requestId;
        private String expectedEvidenceFingerprint;
        private String reason;
        private String sourceReplanId;
        private String sourceReplanNo;
        private String applicantCode;
        private String applicantName;
    }

    /**
     * OMS轮询使用的稳定回执。
     */
    @Data
    @Accessors(chain = true)
    public static class Receipt {
        private Long changeRequestId;
        private String changeRequestNo;
        private Long pmsOrderId;
        private String pmsOrderNo;
        private String requestId;
        private String status;
        private String evidenceFingerprint;
        private String message;
    }
}
