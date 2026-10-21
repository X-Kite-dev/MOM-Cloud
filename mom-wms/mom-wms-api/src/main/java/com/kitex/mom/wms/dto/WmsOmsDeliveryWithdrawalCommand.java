package com.kitex.mom.wms.dto;

import com.kitex.mom.wms.entity.WmsDeliverGoodsPlanBaseEntity;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * OMS撤回WMS发货计划：冻结目标计划事实，按永久请求号获取原执行回执。
 */
public class WmsOmsDeliveryWithdrawalCommand {
    @Data
    @Accessors(chain = true)
    public static class Command {
        private String requestId;
        private String reason;
        private WmsDeliverGoodsPlanBaseEntity expectedPlan;
    }

    @Data
    @Accessors(chain = true)
    public static class Receipt {
        private String requestId;
        private Long planId;
        private String orderCode;
        private Long orderDetailId;
        private String status;
        private String requestFingerprint;
        private LocalDateTime completedTime;
    }
}
