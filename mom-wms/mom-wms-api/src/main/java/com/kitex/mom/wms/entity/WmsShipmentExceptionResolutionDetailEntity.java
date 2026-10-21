package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * 客户实收异常逐来源行的物理退回、履约补偿和责任追偿数量。
 */
@Data
@Accessors(chain = true)
@TableName("wms_shipment_exception_resolution_detail")
@EqualsAndHashCode(callSuper = true)
public class WmsShipmentExceptionResolutionDetailEntity extends Model<WmsShipmentExceptionResolutionDetailEntity> {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long caseId;
    private Long receiptDetailId;
    private BigDecimal exceptionNum;
    private BigDecimal returnNum;
    private BigDecimal reissueNum;
    private BigDecimal refundNum;
    private BigDecimal concessionNum;
    private BigDecimal claimNum;
    private BigDecimal refundAmount;
    private BigDecimal concessionAmount;
    private BigDecimal claimAmount;
    private String remark;
}
