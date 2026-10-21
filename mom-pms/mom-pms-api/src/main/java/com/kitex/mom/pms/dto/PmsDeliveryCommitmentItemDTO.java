package com.kitex.mom.pms.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class PmsDeliveryCommitmentItemDTO {
    private BigDecimal committedNum;
    /**
     * 兼容历史接口的承诺日期；新登记必须优先提交分钟级时刻。
     */
    private LocalDate committedDeliveryDate;
    private LocalDateTime committedDeliveryTime;
    private String deliveryTimePrecision;
}
