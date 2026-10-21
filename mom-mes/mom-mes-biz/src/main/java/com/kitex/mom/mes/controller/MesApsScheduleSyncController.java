package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.dto.MesApsLocalScheduleDTO;
import com.kitex.mom.mes.service.MesApsScheduleSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesApsSchedule")
@Tag(name = "APS局部重排MES执行闸门")
public class MesApsScheduleSyncController {
    private final MesApsScheduleSyncService service;

    @Inner
    @GetMapping("/internal/operation-states")
    @Operation(summary = "读取生产工序实际执行状态及权威指纹")
    public R<MesApsLocalScheduleDTO.StateBundle> operationStates(@RequestParam String planNo,
                                                                 @RequestParam String factoryCode) {
        return R.ok(service.operationStates(planNo, factoryCode));
    }

    @Inner
    @PostMapping("/internal/apply")
    @SysLog("APS局部重排同步MES")
    @Operation(summary = "幂等发布尚未执行工序的新APS计划")
    public R<MesApsLocalScheduleDTO.ApplyReceipt> apply(
            @RequestBody MesApsLocalScheduleDTO.ApplyCommand command) {
        return R.ok(service.apply(command));
    }
}
