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

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TenantTable
@TableName("wms_transfer_receipt")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "调拨分批接收批次")
public class WmsTransferReceiptEntity extends BaseEntity<WmsTransferReceiptEntity> {
    private String receiptNo;
    private Long allocationBillId;
    private String allocationBillNo;
    private String requestId;
    private String requestSnapshot;
    private Integer receiptSequence;
    private Boolean finalReceipt;
    private String receiptStatus;
    private Integer lineCount;
    private String receiverCode;
    private String receiverName;
    private LocalDateTime receiptTime;
    private Long varianceCaseId;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
