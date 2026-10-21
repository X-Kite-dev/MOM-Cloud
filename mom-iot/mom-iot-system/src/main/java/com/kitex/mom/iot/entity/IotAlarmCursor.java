package com.kitex.mom.iot.entity;
import com.baomidou.mybatisplus.annotation.*;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("iot_alarm_cursor")
public class IotAlarmCursor extends BaseEntity<IotAlarmCursor> {
    private Long ruleId;
    private Long lastPropertyId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime lastCollectTime;
    private String lastOutcome;
    private Integer breachCount;
    private Integer recoveryCount;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime firstBreachTime;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long firstBreachPropertyId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long activeAlarmId;
    private String factoryCode;
}
