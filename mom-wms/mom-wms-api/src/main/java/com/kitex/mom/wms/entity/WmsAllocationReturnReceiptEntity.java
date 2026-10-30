package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TenantTable
@TableName("wms_allocation_return_receipt")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "源仓分批实物退回确认记录")
public class WmsAllocationReturnReceiptEntity extends BaseEntity<WmsAllocationReturnReceiptEntity> {
    private Long allocationBillId;
    private String requestId;
    private String requestSnapshot;
    private String evidence;
    private BigDecimal returnedQuantity;
    private BigDecimal remainingQuantity;
    private String operatorCode;
    private String operatorName;
    private LocalDateTime confirmedTime;

    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
