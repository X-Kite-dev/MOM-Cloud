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
@TableName("iot_alarm_rule")
public class IotAlarmRule extends BaseEntity<IotAlarmRule> {
    private Long deviceId;
    private String deviceCode;
    private String identifier;
    private String name;
    private String version;
    private String direction;
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private BigDecimal triggerValue;
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private BigDecimal recoveryValue;
    private Integer triggerSamples;
    private Integer recoverySamples;
    private Integer maxGapSeconds;
    private Boolean allowUnspecifiedQuality;
    private String severity;
    private String status;
    private Long authorId;
    private Long reviewerId;
    private String modelFingerprint;
    private String factoryCode;
}
