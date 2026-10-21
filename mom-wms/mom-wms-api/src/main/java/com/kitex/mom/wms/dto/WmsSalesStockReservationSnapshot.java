package com.kitex.mom.wms.dto;

import com.kitex.mom.wms.entity.WmsSalesStockReservationDetailEntity;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.List;

@Data
@Accessors(chain = true)
public class WmsSalesStockReservationSnapshot {
    private Long reservationId;
    private String reservationNo;
    private Long deliverPlanId;
    private String deliverPlanCode;
    private String orderCode;
    private Long orderDetailId;
    private String productCode;
    private String unitCode;
    private BigDecimal targetQuantity;
    private BigDecimal reservedQuantity;
    private BigDecimal consumedQuantity;
    private BigDecimal releasedQuantity;
    private BigDecimal activeQuantity;
    private BigDecimal healthyActiveQuantity;
    private BigDecimal driftQuantity;
    private String healthStatus;
    private String status;
    private String allocationPolicy;
    private List<WmsSalesStockReservationDetailEntity> details;
}
