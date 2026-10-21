package com.kitex.mom.wms.dto;

import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.entity.WmsProductionReturnReceiptOperationEntity;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.List;

/**
 * 生产退料收货终结与差异处置命令。
 */
public final class WmsProductionReturnReceiptCommand {
    private WmsProductionReturnReceiptCommand() {
    }

    @Data
    @Accessors(chain = true)
    public static class Finalize {
        private Long noticeId;
        private String requestId;
        private BigDecimal overageQuantity;
        private String reason;
    }

    @Data
    @Accessors(chain = true)
    public static class Disposition {
        private Long noticeId;
        private String requestId;
        /**
         * ACCEPT_SHORTAGE / CONTINUE_RECEIPT / RETURN_OVERAGE。
         */
        private String decision;
        private String reason;
    }

    @Data
    @Accessors(chain = true)
    public static class RetryDelivery {
        private Long noticeId;
        private String requestId;
        private String reason;
    }

    /**
     * 管理端和PDA共用的收货闭环视图。
     */
    @Data
    @Accessors(chain = true)
    public static class View {
        private WmsInNoticeEntity notice;
        private BigDecimal expectedQuantity;
        private BigDecimal actualReceivedQuantity;
        private BigDecimal shortageQuantity;
        private BigDecimal overageQuantity;
        private Boolean hasOpenBill;
        private String deliveryEventId;
        private String deliveryStatus;
        private String deliveryLastError;
        private Integer deliveryAttemptCount;
        private List<WmsProductionReturnReceiptOperationEntity> operations;
    }
}
