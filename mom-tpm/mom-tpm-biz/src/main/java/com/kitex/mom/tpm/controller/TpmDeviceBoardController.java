package com.kitex.mom.tpm.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.tpm.dto.*;
import com.kitex.mom.tpm.service.TpmCalendarService;
import com.kitex.mom.tpm.service.TpmDeviceBoardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


/**
 * 设备看板接口
 *
 * @author kitex
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmDeviceBoard")
@Tag(description = "tpmDeviceBoard", name = "设备看板接口")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmDeviceBoardController {

    private final TpmDeviceBoardService tpmDeviceBoardService;
    private final TpmCalendarService tpmCalendarService;


    /**
     * 今日报修-统计
     */
    @Operation(summary = "今日报修-统计", description = "今日报修-统计")
    @GetMapping("/repair/summary")
    public R<RepairSummaryDto> repairSummary() {
        return R.ok(tpmDeviceBoardService.repairSummary());
    }


    /**
     * 设备使用状态
     */
    @Operation(summary = "设备使用状态", description = "设备使用状态")
    @GetMapping("/equipmentUsage/usage")
    public R<EquipmentUsageDto> equipmentUsage() {
        return R.ok(tpmDeviceBoardService.equipmentUsage());
    }


    /**
     * 设备总览
     */
    @Operation(summary = "设备总览", description = "设备总览")
    @GetMapping("/equipmentOverview")
    public R<EquipmentOverviewDto> equipmentOverview() {
        return R.ok(tpmDeviceBoardService.equipmentOverview());
    }


    /**
     * 本月故障次数排名
     */
    @Operation(summary = "本月故障次数排名", description = "本月故障次数排名")
    @GetMapping("/failureRanking")
    public R<List<EquipmentBoardSortDto>> failureRanking() {
        return R.ok(tpmDeviceBoardService.failureRanking());
    }

    /**
     * 本月停机排名
     */
    @Operation(summary = "本月停机排名", description = "本月停机排名")
    @GetMapping("/downtimeRanking")
    public R<List<EquipmentBoardSortDto>> downtimeRanking() {
        return R.ok(tpmDeviceBoardService.downtimeRanking());
    }


    /**
     * 待办|公告  /sysMessage/user/page
     */


    /**
     * 备件更换
     */
    @Operation(summary = "备件更换", description = "备件更换")
    @GetMapping("/sparePartsReplacement")
    public R<SparePartsReplacementDto> sparePartsReplacement() {
        return R.ok(tpmDeviceBoardService.sparePartsReplacement());
    }

    /**
     * 保养情况
     */
    @Operation(summary = "保养情况", description = "保养情况")
    @GetMapping("/maintenanceStatus")
    public R<MaintenanceStatusDto> maintenanceStatus() {
        return R.ok(tpmDeviceBoardService.maintenanceStatus());
    }

    /**
     * 巡检情况
     */
    @Operation(summary = "巡检情况", description = "巡检情况")
    @GetMapping("/inspectionStatus")
    public R<InspectionStatusDto> inspectionStatus() {
        return R.ok(tpmDeviceBoardService.inspectionStatus());
    }


    /**
     * 根据日期查询日历信息
     */
    @Operation(summary = "根据日期查询日历信息", description = "根据日期查询日历信息")
    @GetMapping("/equipmentCalendar")
    public R<TpmCalendarDto> equipmentCalendar() {
        return R.ok(tpmCalendarService.getCalendarMsg(null));
    }


}