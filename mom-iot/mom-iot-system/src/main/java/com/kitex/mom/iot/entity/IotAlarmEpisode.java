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
@TableName("iot_alarm_episode")
public class IotAlarmEpisode extends BaseEntity<IotAlarmEpisode> {
    private Long ruleId;
    private Long deviceId;
    private String deviceCode;
    private String identifier;
    private String name;
    private String severity;
    private String status;
    private String ruleSnapshot;
    private Long firstPropertyId;
    private Long lastPropertyId;
    private Long recoveryPropertyId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime firstBreachTime;
    private LocalDateTime triggeredTime;
    private LocalDateTime lastTriggerTime;
    private LocalDateTime recoveredTime;
    private LocalDateTime closedTime;
    private Long acknowledgedBy;
    private LocalDateTime acknowledgedTime;
    private Long verifiedBy;
    private Long observationCount;
    private String factoryCode;
}

