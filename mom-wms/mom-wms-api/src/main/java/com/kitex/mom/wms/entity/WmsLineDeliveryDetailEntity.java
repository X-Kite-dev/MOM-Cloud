package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * 线边配送逐WMS出库明细的到达与异常处置账。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("wms_line_delivery_detail")
@EqualsAndHashCode(callSuper = true)
public class WmsLineDeliveryDetailEntity extends BaseEntity<WmsLineDeliveryDetailEntity> {
    private Long taskId;
    private Long outBillDetailId;
    private String matCode;
    private String matName;
    private String matBatch;
    private String matUnit;
    private BigDecimal plannedNum;
    private BigDecimal deliveredNum;
    private BigDecimal shortNum;
    private BigDecimal damagedNum;
    private BigDecimal recoveredNum;
    private BigDecimal returnedNum;
    private BigDecimal lossNum;
    private String responsibility;
    private String exceptionReason;
    private String resolutionReference;
    private String resolutionRemark;
}
