package com.kitex.mom.iot.controller;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.iot.dto.IotDataScopeCommand;
import com.kitex.mom.iot.service.impl.IotDataScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
@RestController @RequiredArgsConstructor @RequestMapping("/data-scope")
@PreAuthorize("@pms.hasPermission('iot_dataScope_view')")
public class IotDataScopeController {
    private final IotDataScopeService service;
    @GetMapping("/candidates") public R<?> candidates(@RequestParam Long deviceId,@RequestParam String sourceKind,@RequestParam(required=false) Long beforeId){return R.ok(service.candidates(deviceId,sourceKind,beforeId));}
    @PostMapping("/preview") @SysLog("核对待认领原始数据证据")
    public R<?> preview(@RequestBody IotDataScopeCommand c){return R.ok(service.preview(c));}
    @GetMapping("/page") public R<?> page(@RequestParam String factoryCode,@RequestParam(defaultValue="1") long current){return R.ok(service.page(factoryCode,current));}
    @GetMapping("/{id}") public R<?> detail(@PathVariable Long id){return R.ok(service.detail(id));}
    @PostMapping("/submit") @SysLog("申请认领历史设备数据")
    public R<?> submit(@RequestBody IotDataScopeCommand c){return R.ok(service.submit(c));}
    @PostMapping("/approve") @SysLog("异岗复核认领设备数据")
    public R<?> approve(@RequestBody IotDataScopeCommand c){return R.ok(service.approve(c));}
    @PostMapping("/reject") @SysLog("驳回历史归属认领")
    public R<?> reject(@RequestBody IotDataScopeCommand c){return R.ok(service.reject(c));}
    @PostMapping("/withdraw") @SysLog("撤回历史归属认领")
    public R<?> withdraw(@RequestBody IotDataScopeCommand c){return R.ok(service.withdraw(c));}
}

