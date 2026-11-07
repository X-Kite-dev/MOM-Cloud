package com.kitex.mom.mdm.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mdm.dto.MdmReceivingLocationDTO.*;
import com.kitex.mom.mdm.service.impl.MdmReceivingLocationService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmStationReceiving")
public class MdmReceivingLocationController {
    private final MdmReceivingLocationService service;

    @GetMapping("/station/{stationId}")
    @PreAuthorize("@pms.hasPermission('mdm_factoryModel_view')")
    public R overview(@PathVariable String stationId) {
        return R.ok(service.overview(stationId));
    }

    @GetMapping("/reference/{stationId}/{location}")
    @PreAuthorize("@pms.hasPermission('mdm_factoryModel_view')")
    public R reference(@PathVariable String stationId, @PathVariable String location) {
        return R.ok(service.reference(stationId, location));
    }

    @GetMapping("/locations/{stationId}")
    @PreAuthorize("@pms.hasPermission('mdm_factoryModel_view')")
    public R locations(
            @PathVariable String stationId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String afterId) {
        return R.ok(service.locations(stationId, search, afterId));
    }

    @GetMapping("/history/{station}/{location}")
    @PreAuthorize("@pms.hasPermission('mdm_factoryModel_view')")
    public R history(
            @PathVariable String station,
            @PathVariable String location,
            @RequestParam(required = false) String beforeRevision) {
        return R.ok(service.history(station, location, beforeRevision));
    }

    @GetMapping("/runtime/{stationCode}")
    @PreAuthorize("isAuthenticated()")
    public R<RuntimeView> runtime(@PathVariable String stationCode) {
        return R.ok(service.runtime(stationCode));
    }

    @PostMapping("/receipt")
    @PreAuthorize("@pms.hasPermission('mdm_factoryModel_view')")
    public R receipt(@RequestBody Command c) {
        return R.ok(service.inspect(c));
    }

    @PostMapping("/execute")
    @PreAuthorize("@pms.hasPermission('mdm_factoryModel_edit')")
    @SysLog("维护工位收货位置")
    public R execute(@RequestBody Command c) {
        return R.ok(service.execute(c));
    }

    @PostMapping("/cancel")
    @PreAuthorize("@pms.hasPermission('mdm_factoryModel_edit')")
    @SysLog("终止未执行的收货位置原请求")
    public R cancel(@RequestBody Cancel c) {
        return R.ok(service.cancel(c));
    }
}
