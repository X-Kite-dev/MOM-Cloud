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
@TableName("iot_alarm_sample")
public class IotAlarmSample extends BaseEntity<IotAlarmSample> {
    private Long ruleId;
    private Long propertyId;
    private Long alarmId;
    private String fingerprint;
    private String valueText;
    private String qualityCode;
    private String outcome;
    private LocalDateTime collectTime;
    private String factoryCode;
}

