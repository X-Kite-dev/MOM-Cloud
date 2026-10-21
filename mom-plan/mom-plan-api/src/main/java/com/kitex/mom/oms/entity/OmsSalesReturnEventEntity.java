package com.kitex.mom.oms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** WMS销售退货实收事实OMS永久收件箱。 */
@Data
@Accessors(chain = true)
@TenantTable
@EqualsAndHashCode(callSuper = true)
@TableName("oms_sales_return_event")
public class OmsSalesReturnEventEntity extends BaseEntity<OmsSalesReturnEventEntity> {
    private String requestId;
    private String requestFingerprint;
    private Long wmsInBillId;
    private Long wmsInBillDetailId;
    private String wmsInBillNo;
    private Long wmsReturnId;
    private Long resolutionCaseId;
    private Long wmsReturnDetailId;
    private Long wmsPlanId;
    private String wmsPlanCode;
    private String orderCode;
    private Long orderDetailId;
    private String productCode;
    private BigDecimal returnedNum;
    private LocalDateTime returnedTime;
    private BigDecimal omsDeliveredNum;
    private String status;
    private LocalDateTime completedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
