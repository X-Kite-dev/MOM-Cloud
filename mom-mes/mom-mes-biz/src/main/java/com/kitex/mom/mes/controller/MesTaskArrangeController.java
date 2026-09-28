package com.kitex.mom.mes.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.MesChangeoverExecutionEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskExecutionSegmentEntity;
import com.kitex.mom.mes.entity.MesToolingExecutionEntity;
import com.kitex.mom.mes.service.MesTaskArrangeService;
import com.kitex.mom.tpm.dto.DeviceCheckTaskStartDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 生产派工
 *
 * @author kitex
 * @since 2025-05-06
 */
@Tag(description = "mesTaskArrange", name = "生产派工")
@RestController
@RequestMapping("/mesTask/arrange")
@RequiredArgsConstructor
public class MesTaskArrangeController {

    private final MesTaskArrangeService service;


    @Operation(summary = "生产派工分页", description = "生产派工分页")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<IPage<MesTaskArrangeEntity>> page(Page<MesTaskArrangeEntity> pageParam, MesTaskArrangeEntity query) {
        QueryWrapper<MesTaskArrangeEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(query);
        autoQueryWrapper.lambda().orderByDesc(MesTaskArrangeEntity::getCreateTime);
        return R.ok(service.page(pageParam, autoQueryWrapper));
    }

    /**
     * pad 派工分页
     *
     * @param pageParam
     * @param query
     * @return
     */
    @Operation(summary = "生产派工分页", description = "生产派工分页")
    @GetMapping("/pad/page")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<IPage<MesTaskArrangeEntity>> padPage(Page<MesTaskArrangeEntity> pageParam, MesTaskArrangeEntity query) {
        QueryWrapper<MesTaskArrangeEntity> autoQueryWrapper = new QueryWrapper<>();
        MomUser user = SecurityUtils.getUser();
        autoQueryWrapper
                .apply("{0} = ANY(string_to_array(worker_code, ','))", user.getUsername())
                .eq(StrUtil.isNotEmpty(query.getStatus()), "status", query.getStatus())
                .eq(StrUtil.isNotEmpty(query.getShiftCode()), "shift_code", query.getShiftCode())
                .eq(StrUtil.isNotEmpty(query.getWorkshopCode()), "workshop_code", query.getWorkshopCode())
                .apply(StrUtil.isNotEmpty(query.getWorksiteCodes()),
                        "{0} = ANY(string_to_array(worksite_codes, ','))", query.getWorksiteCodes())
                .orderByDesc("create_time");
        return R.ok(service.page(pageParam, autoQueryWrapper));
    }

    @Operation(summary = "查询详情", description = "查询详情")
    @GetMapping("/findById")
    @PreAuthorize("@pms.hasPermission('mesProduceTask:show')")
    public R<MesTaskArrangeEntity> findById(Long id) {
        return R.ok(service.getById(id));
    }

    @Operation(summary = "派工列表", description = "派工列表")
    @GetMapping("/list")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<List<MesTaskArrangeEntity>> list(MesTaskArrangeEntity query) {
        QueryWrapper<MesTaskArrangeEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(query);
        autoQueryWrapper.lambda().orderByDesc(MesTaskArrangeEntity::getCreateTime);
        return R.ok(service.list(autoQueryWrapper));
    }

    @Operation(summary = "派工明细", description = "派工明细")
    @GetMapping("/arrangeDetail")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<List<MesTaskArrangeEntity>> arrangeDetail(String produceTaskCode) {
        return R.ok(service.list(new LambdaQueryWrapper<MesTaskArrangeEntity>().eq(MesTaskArrangeEntity::getProduceTaskCode, produceTaskCode)));
    }

    @Operation(summary = "派工", description = "派工")
    @PostMapping("/arrange")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:arrange')")
    public R<Void> arrange(@RequestBody MesTaskArrangeDTO dto) {
        service.arrange(dto);
        return R.ok();
    }

    @Operation(summary = "排产发布列表", description = "排产发布列表")
    @GetMapping("/statusList")
    @PreAuthorize("@pms.hasPermission('mesProduceTask:show')")
    public R<List<MesTaskArrangeStatusDTO>> queryStatusList(String worksiteCode, String shiftCode, String workshopCode) {
        return R.ok(service.queryStatusList(worksiteCode, shiftCode, workshopCode));
    }

    @Operation(summary = "查询详情", description = "查询详情")
    @GetMapping("/getCurrentTask")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<MesArrangeTaskVO> getCurrentTask(String shiftCode, String workshopCode, String worksiteCode) {
        return R.ok(service.getCurrentTask(shiftCode, workshopCode, worksiteCode));
    }

    @Operation(summary = "查询详情", description = "查询详情")
    @GetMapping("/getCurrentDevice")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<MesArrangeDeviceVO> getCurrentDevice(String shiftCode, String workshopCode, String worksiteCode) {
        return R.ok(service.getRunningDevice(shiftCode, workshopCode, worksiteCode));
    }

    @Operation(summary = "查询详情", description = "查询详情")
    @GetMapping("/getBomList")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<List<MdmBomChildListDTO>> getBomList(String code) {
        return R.ok(service.getBomList(code));
    }

    @Operation(summary = "查询详情", description = "查询详情")
    @GetMapping("/getBomListByTaskCode")
    public R<List<MdmBomChildListDTO>> getBomListByTaskCode(String code) {
        return R.ok(service.getBomListByTaskCode(code));
    }

    @Operation(summary = "任务接收", description = "任务接收")
    @PostMapping("/receive")
    @PreAuthorize("@pms.hasPermission('mesProduceTask:receive')")
    public R<Void> receive(Long id, String worksiteCode) {
        service.receive(id, worksiteCode);
        return R.ok();
    }

    @Operation(summary = "按APS锁定结果扫码领用工装")
    @PostMapping("/tooling/check-in")
    public R<Void> checkInToolings(@RequestBody MesToolingCheckInDTO dto) {
        service.checkInToolings(dto);
        return R.ok();
    }

    @Operation(summary = "查询派工任务工装执行记录")
    @GetMapping("/tooling/executions")
    public R<List<MesToolingExecutionEntity>> listToolingExecutions(Long arrangeId) {
        return R.ok(service.listToolingExecutions(arrangeId));
    }

    @Operation(summary = "任务开工", description = "任务开工")
    @PostMapping("/start")
    @PreAuthorize("@pms.hasPermission('mesProduceTask:start')")
    public R<Void> start(Long id) {
        service.start(id);
        return R.ok();
    }

    @Operation(summary = "查询派工连续生产时间段")
    @GetMapping("/execution/segments")
    public R<List<MesTaskExecutionSegmentEntity>> executionSegments(Long arrangeId) {
        return R.ok(service.listExecutionSegments(arrangeId));
    }

    @Operation(summary = "暂停", description = "暂停")
    @PostMapping("/pause")
    @PreAuthorize("@pms.hasPermission('mesProduceTask:pause')")
    public R<Void> pause(Long id) {
        service.pause(id);
        return R.ok();
    }

    @Operation(summary = "关闭", description = "暂停")
    @PostMapping("/close")
    @PreAuthorize("@pms.hasPermission('mesProduceTask:close')")
    public R<Void> close(Long id) {
        service.close(id);
        return R.ok();
    }

    @Operation(summary = "完工", description = "完工")
    @PostMapping("/finish")
    public R<Void> finish(Long id) {
        service.finish(id);
        return R.ok();
    }

    @Operation(summary = "设备开机/换模", description = "设备开机/换模")
    @PostMapping("/deviceStart")
    public R<Void> deviceStart(@RequestBody DeviceCheckTaskStartDto deviceCheckTaskStartDto) {
        service.deviceStart(deviceCheckTaskStartDto);
        return R.ok();
    }

    @Operation(summary = "开始换型并建立计时账")
    @PostMapping("/changeover/begin")
    public R<MesChangeoverExecutionEntity> beginChangeover(@RequestBody MesChangeoverStartDTO dto) {
        return R.ok(service.beginChangeover(dto));
    }

    @Operation(summary = "取消未完成换型")
    @PostMapping("/changeover/cancel")
    public R<Void> cancelChangeover(@RequestBody MesChangeoverCancelDTO dto) {
        service.cancelChangeover(dto);
        return R.ok();
    }

    @Operation(summary = "派工班组查询", description = "派工班组查询")
    @GetMapping("/arrangeShift")
    public R<List<String>> arrangeShift(String produceTaskCode) {
        return R.ok(service.list(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                        .eq(MesTaskArrangeEntity::getProduceTaskCode, produceTaskCode))
                .stream().map(MesTaskArrangeEntity::getShiftCode).toList());
    }
}
