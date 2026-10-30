package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

@Data
@Accessors(chain = true)
@TenantTable
@TableName("wms_allocation_return_exception_line")
@EqualsAndHashCode(callSuper = true)
public class WmsAllocationReturnExceptionLineEntity extends BaseEntity<WmsAllocationReturnExceptionLineEntity> {
    private Long caseId;
    private Long sourceFlowId;
    private String materialCode;
    private String materialBatch;
    private String materialBarcode;
    private Boolean serialManaged;
    private String stockOwnerType;
    private String stockOwnerCode;
    private String sourceBusinessNo;
    private BigDecimal issuedQuantity;
    private BigDecimal receivedQuantity;
    private BigDecimal returnedQuantity;
    private BigDecimal exceptionQuantity;

    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
