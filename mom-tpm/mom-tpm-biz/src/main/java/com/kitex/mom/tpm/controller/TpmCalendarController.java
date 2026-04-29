package com.kitex.mom.tpm.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.tpm.dto.TpmCalendarDto;
import com.kitex.mom.tpm.entity.TpmCheckTaskEntity;
import com.kitex.mom.tpm.entity.TpmMaintenanceTaskEntity;
import com.kitex.mom.tpm.entity.TpmRepairTaskEntity;
import com.kitex.mom.tpm.service.TpmCalendarService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


/**
 * 设备日历
 *
 * @author kitex
 * @date 2025-03-19 11:10:04
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmCalendar")
@Tag(description = "tpmCalendar", name = "设备日历")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmCalendarController {

    private final TpmCalendarService tpmCalendarService;

    /**
     * 根据日期查询日历信息
     */
    @Operation(summary = "根据日期查询日历信息", description = "根据日期查询日历信息")
    @GetMapping("/getCalendarMsg")
    public R<TpmCalendarDto> getCalendarMsg(String date) {
        return R.ok(tpmCalendarService.getCalendarMsg(date));
    }


    /**
     * 根据所传日期查询保养任务
     */
    @Operation(summary = "根据所传日期查询保养任务", description = "根据所传日期查询保养任务")
    @GetMapping("/getMaintenanceTaskByDay")
    public R<List<TpmMaintenanceTaskEntity>> getMaintenanceTaskByDay(@RequestParam("date") String date) {
        return R.ok(tpmCalendarService.getMaintenanceTaskByDay(date));
    }

    /**
     * 根据所传日期查询维修任务
     */
    @Operation(summary = "根据所传日期查询维修任务", description = "根据所传日期查询维修任务")
    @GetMapping("/getRepairTaskByDay")
    public R<List<TpmRepairTaskEntity>> getRepairTaskByDay(@RequestParam("date") String date) {
        return R.ok(tpmCalendarService.getRepairTaskByDay(date));
    }

    /**
     * 根据所传日期查询点/巡任务
     */
    @Operation(summary = "根据所传日期查询点/巡任务", description = "根据所传日期查询点/巡任务")
    @GetMapping("/getCheckTaskByDay")
    public R<List<TpmCheckTaskEntity>> getCheckTaskByDay(@RequestParam("date") String date,
                                                         @RequestParam("type") String type) {
        return R.ok(tpmCalendarService.getCheckTaskByDay(date, type));
    }

}