package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * 成品入库红冲对原报工来源分配的不可变反向明细。
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("mes_task_in_wms_receipt_reverse_detail")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "MES成品入库原报工来源的不可变反向明细")
public class MesTaskInWmsReceiptReverseDetailEntity extends BaseEntity<MesTaskInWmsReceiptReverseDetailEntity> {
    @Schema(description = "MES红冲Inbox ID")
    private Long inboxId;
    @Schema(description = "MES原成品入库回执ID")
    private Long receiptId;
    @Schema(description = "MES原报工来源分配明细ID")
    private Long receiptDetailId;
    @Schema(description = "末工序原有效报工ID")
    private Long reportId;
    @Schema(description = "从该报工来源释放的数量")
    private BigDecimal reversedQuantity;
    @Schema(description = "实际工厂编码")
    private String factoryCode;
}
