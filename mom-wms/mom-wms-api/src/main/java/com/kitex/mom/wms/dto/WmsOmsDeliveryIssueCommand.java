package com.kitex.mom.wms.dto;

import com.kitex.mom.wms.entity.WmsDeliverGoodsPlanBaseEntity;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * OMS发货计划下发/同步：请求号和冻结计划必须由OMS协调账持久保存。
 */
public class WmsOmsDeliveryIssueCommand {
    @Data
    @Accessors(chain = true)
    public static class Command {
        private String requestId;
        /**
         * CREATE 或 UPDATE。
         */
        private String action;
        private WmsDeliverGoodsPlanBaseEntity expectedPlan;
        private WmsDeliverGoodsPlanBaseEntity desiredPlan;
    }

    @Data
    @Accessors(chain = true)
    public static class Receipt {
        private String requestId;
        private String action;
        private Long planId;
        private String orderCode;
        private Long orderDetailId;
        private String planCode;
        private String requestFingerprint;
        private String status;
        private LocalDateTime completedTime;
    }
}
