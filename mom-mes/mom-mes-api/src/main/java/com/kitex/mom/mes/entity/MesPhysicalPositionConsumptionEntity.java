package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * 已确认母件位置的一次永久处置；同一位置只能由一个正式转换执行消费。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("mes_physical_position_consumption")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "母件位置消费结果")
public class MesPhysicalPositionConsumptionEntity extends BaseEntity<MesPhysicalPositionConsumptionEntity> {
    private String factoryCode;
    private Long surveyId;
    private String positionCode;
    private Long executionId;
    private Long outputId;
    private String disposition;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String outputIdentifier;
}
