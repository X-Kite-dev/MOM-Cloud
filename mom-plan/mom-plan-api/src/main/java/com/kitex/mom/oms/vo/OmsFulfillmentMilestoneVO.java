package com.kitex.mom.oms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 履约阶段只表达可核验事实；数据源不可用时明确UNKNOWN，不猜测完成。
 */
@Data
@Schema(description = "订单履约里程碑")
public class OmsFulfillmentMilestoneVO implements Serializable {
    private Integer sequence;
    private String milestoneCode;
    private String milestoneName;
    /**
     * NOT_STARTED、IN_PROGRESS、COMPLETED、BLOCKED、UNKNOWN。
     */
    private String status;
    private String sourceSystem;
    private String sourceNo;
    private BigDecimal targetQuantity;
    private BigDecimal actualQuantity;
    private LocalDateTime plannedTime;
    private LocalDateTime actualTime;
    private String message;
}
