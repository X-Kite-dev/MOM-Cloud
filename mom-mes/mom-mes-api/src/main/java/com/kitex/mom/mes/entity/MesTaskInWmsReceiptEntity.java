package com.kitex.mom.mes.entity;

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

/**
 * MES成品入库命令永久幂等凭证。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("mes_task_in_wms_receipt")
@EqualsAndHashCode(callSuper = true)
public class MesTaskInWmsReceiptEntity extends BaseEntity<MesTaskInWmsReceiptEntity> {
    private String requestId;
    private String requestFingerprint;
    private Long mesTaskId;
    private String mesTaskCode;
    private String apsBatchNo;
    private String warehouseCode;
    private BigDecimal inStockQuantity;
    private LocalDateTime handledTime;
    /**
     * ACTIVE/PARTIALLY_REVERSED/REVERSED；历史空值按ACTIVE处理。
     */
    private String reversalStatus;
    /**
     * 最近一次已接收红冲事件号；每个永久事件仍以Inbox独立防重。
     */
    private String reverseEventId;
    /**
     * 累计已红冲数量。
     */
    private BigDecimal reversedQuantity;
    /**
     * 最近一次红冲被MES接收的时间。
     */
    private LocalDateTime reversedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
