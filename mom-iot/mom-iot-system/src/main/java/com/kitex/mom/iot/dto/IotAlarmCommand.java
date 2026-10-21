package com.kitex.mom.iot.dto;
import com.kitex.mom.iot.entity.IotAlarmRule;
import lombok.Data;
@Data
public class IotAlarmCommand {
    private String requestId;
    private String reason;
    private Long ruleId;
    private Long alarmId;
    private IotAlarmRule rule;
}
