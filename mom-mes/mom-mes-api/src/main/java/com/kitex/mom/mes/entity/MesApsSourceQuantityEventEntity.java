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
 * APS/MES执行数量按原计划归属后的永久事件分录。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("mes_aps_source_quantity_event")
@EqualsAndHashCode(callSuper = true)
public class MesApsSourceQuantityEventEntity extends BaseEntity<MesApsSourceQuantityEventEntity> {
    private String requestId;
    private String requestFingerprint;
    private String eventKind;
    private Long mesTaskId;
    private String mesTaskCode;
    private String apsBatchNo;
    private Long sourceIssueId;
    private String sourceIssueNo;
    private BigDecimal quantityDelta;
    private BigDecimal quantityAfter;
    private String sourceReference;
    private String eventContent;
    private LocalDateTime occurredTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
