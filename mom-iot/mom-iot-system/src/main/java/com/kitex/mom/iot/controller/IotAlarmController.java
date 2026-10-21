package com.kitex.mom.iot.controller;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.iot.dto.IotAlarmCommand;
import com.kitex.mom.iot.service.impl.IotAlarmService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController
@RequiredArgsConstructor
@RequestMapping("/alarm")
public class IotAlarmController {
    private final IotAlarmService service;
    @GetMapping("/devices") @PreAuthorize("@pms.hasPermission('iot_alarm_view')")
    public R<?> devices(@RequestParam String factoryCode,@RequestParam(required=false) String search){return R.ok(service.devices(factoryCode,search));}
    @GetMapping("/rule/{id}") @PreAuthorize("@pms.hasPermission('iot_alarm_view')")
    public R<?> ruleDetail(@PathVariable Long id){return R.ok(service.ruleDetail(id));}
    @GetMapping("/rules") @PreAuthorize("@pms.hasPermission('iot_alarm_view')")
    public R<?> rules(@RequestParam String factoryCode,@RequestParam(defaultValue="1") long current,@RequestParam(defaultValue="20") long size){return R.ok(service.rules(factoryCode,current,size));}
    @GetMapping("/page") @PreAuthorize("@pms.hasPermission('iot_alarm_view')")
    public R<?> page(@RequestParam String factoryCode,@RequestParam(required=false) String status,@RequestParam(required=false) String deviceCode,
                     @RequestParam(defaultValue="1") long current,@RequestParam(defaultValue="20") long size){return R.ok(service.alarms(factoryCode,status,deviceCode,current,size));}
    @GetMapping("/summary") @PreAuthorize("@pms.hasPermission('iot_alarm_view')")
    public R<?> summary(@RequestParam String factoryCode){return R.ok(service.summary(factoryCode));}
    @GetMapping("/{id}") @PreAuthorize("@pms.hasPermission('iot_alarm_view')")
    public R<?> detail(@PathVariable Long id){return R.ok(service.detail(id));}
    @GetMapping("/samples/{ruleId}") @PreAuthorize("@pms.hasPermission('iot_alarm_view')")
    public R<?> samples(@PathVariable Long ruleId,@RequestParam(defaultValue="1") long current,@RequestParam(defaultValue="20") long size){return R.ok(service.samples(ruleId,current,size));}
    @PostMapping("/rule") @SysLog("创建真实采集告警规则") @PreAuthorize("@pms.hasPermission('iot_alarm_rule')")
    public R<?> create(@RequestBody IotAlarmCommand c){return R.ok(service.createRule(c));}
    @PostMapping("/activate") @SysLog("异岗启用告警规则") @PreAuthorize("@pms.hasPermission('iot_alarm_ruleReview')")
    public R<?> activate(@RequestBody IotAlarmCommand c){return R.ok(service.ruleAction(c,true));}
    @PostMapping("/retire") @SysLog("退役告警规则") @PreAuthorize("@pms.hasPermission('iot_alarm_ruleReview')")
    public R<?> retire(@RequestBody IotAlarmCommand c){return R.ok(service.ruleAction(c,false));}
    @PostMapping("/acknowledge") @SysLog("确认真实采集告警") @PreAuthorize("@pms.hasPermission('iot_alarm_acknowledge')")
    public R<?> acknowledge(@RequestBody IotAlarmCommand c){return R.ok(service.alarmAction(c,false));}
    @PostMapping("/verify") @SysLog("异岗复核告警恢复") @PreAuthorize("@pms.hasPermission('iot_alarm_verify')")
    public R<?> verify(@RequestBody IotAlarmCommand c){return R.ok(service.alarmAction(c,true));}
}
