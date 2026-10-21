package com.kitex.mom.pms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TenantTable
@TableName("pms_delivery_commitment_item")
@EqualsAndHashCode(callSuper = true)
public class PmsDeliveryCommitmentItemEntity extends BaseEntity<PmsDeliveryCommitmentItemEntity> {
    private Long commitmentId;
    private Integer sequenceNo;
    private BigDecimal committedNum;
    private LocalDate committedDeliveryDate;
    private LocalDateTime committedDeliveryTime;
    /**
     * MINUTE 或 DATE_END；DATE_END表示历史日期按日末保守换算。
     */
    private String deliveryTimePrecision;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
