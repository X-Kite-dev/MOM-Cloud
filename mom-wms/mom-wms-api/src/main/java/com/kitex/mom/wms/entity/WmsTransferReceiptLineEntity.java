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
@TableName("wms_transfer_receipt_line")
@EqualsAndHashCode(callSuper = true)
public class WmsTransferReceiptLineEntity extends BaseEntity<WmsTransferReceiptLineEntity> {
    private Long receiptId;
    private Long allocationBillId;
    private Long sourceFlowId;
    private String materialCode;
    private String materialName;
    private String materialBatch;
    private String materialUnit;
    private BigDecimal issuedQuantity;
    private BigDecimal receivedBeforeQuantity;
    private BigDecimal actualReceivedQuantity;
    private BigDecimal acceptedQuantity;
    private BigDecimal overQuantity;
    private BigDecimal remainingAfterQuantity;
    private String targetWarehouseCode;
    private String targetSiteCode;
    private String targetSiteName;
    private String targetContainerCode;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
