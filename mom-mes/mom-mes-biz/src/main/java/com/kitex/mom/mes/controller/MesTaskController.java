package com.kitex.mom.mes.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.mom.mes.batch.BatchGeneratorService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.service.MesTaskService;
import com.kitex.mom.mes.vo.MesTaskReportOmsVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 生产工单
 *
 * @author kitex
 * @since 2025-05-06
 */
@Tag(description = "mesTask", name = "生产工单")
@RestController
@RequestMapping("/mesTask")
@RequiredArgsConstructor
public class MesTaskController {

    private final MesTaskService service;
    private final BatchGeneratorService batchGeneratorService;


    @Operation(summary = "生产工单分页", description = "生产工单分页")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mesTask:show')")
    public R<IPage<MesTaskEntity>> page(Page<MesTaskEntity> pageParam, MesTaskEntity query) {
        return R.ok(service.queryPage(pageParam, query));
    }

    @Operation(summary = "生产工单接收", description = "生产工单接收")
    @PostMapping("/add")
    public R<Void> add(@RequestBody MesTaskReceiveDTO receive) {
        service.add(receive);
        return R.ok();
    }

    @Operation(summary = "生产工单编辑", description = "生产工单编辑")
    @PostMapping("/update")
    @PreAuthorize("@pms.hasPermission('mesTask:update')")
    public R<Void> update(@RequestBody MesTaskEntity mesTask) {
        service.edit(mesTask);
        return R.ok();
    }

    @Operation(summary = "生产工单编辑", description = "生产工单编辑")
    @GetMapping("/getById")
    @PreAuthorize("@pms.hasPermission('mesTask:show')")
    public R<MesTaskDTO> getById(Long id) {
        return R.ok(service.findBydId(id));
    }

    /**
     * 根据任务编码获取工单详情
     *
     * @param taskCode
     * @return
     */
    @Operation(summary = "根据任务编码获取工单详情", description = "根据任务编码获取工单详情")
    @GetMapping("/getByTaskCode")
    @PreAuthorize("@pms.hasPermission('mesTask:show')")
    public R<MesTaskDTO> getByTaskCode(@RequestParam("taskCode") String taskCode) {
        return R.ok(service.getByTaskCode(taskCode));
    }

    @Operation(summary = "下达任务", description = "下达任务")
    @PostMapping("/issue")
    @PreAuthorize("@pms.hasPermission('mesTask:issue')")
    public R<Void> issue(Long id) {
        service.issue(id);
        return R.ok();
    }

    @Operation(summary = "关闭任务", description = "关闭任务")
    @PostMapping("/close")
    @PreAuthorize("@pms.hasPermission('mesTask:close')")
    public R<Void> close(Long id) {
        service.close(id);
        return R.ok();
    }

    /**
     * 生产工单入库
     *
     * @param mesTaskInWmsDTO 生产工单入库DTO
     * @return R
     */
    @Operation(summary = "入库", description = "入库")
    @PostMapping("/inWms")
    @PreAuthorize("@pms.hasPermission('mesTask:update')")
    public R<Boolean> inWms(@RequestBody MesTaskInWmsDTO mesTaskInWmsDTO) {
        return R.ok(service.inWms(mesTaskInWmsDTO));
    }

    /**
     * 根据生产订单号查询生产工单最后一道工序的报工记录
     *
     * @param orders 生产订单列表
     * @return R
     */
    @Operation(summary = "根据生产订单号查询生产工单最后一道工序的报工记录", description = "根据生产订单号查询生产工单最后一道工序的报工记录")
    @GetMapping("/getByOrders")
    public R<List<MesTaskReportOmsVO>> getByOrders(@RequestParam("orders") List<String> orders) {
        return R.ok(service.getByOrders(orders));
    }

    /**
     * 生产工单对接生产计划接口
     *
     * @param planMesQueryDTO 查询条件
     * @return R
     */
    @Operation(summary = "生产工单对接生产计划接口", description = "生产工单对接生产计划接口")
    @GetMapping("/plan/list")
    public R<List<MesTaskEntity>> planMesList(@ParameterObject PlanMesQueryDTO planMesQueryDTO) {
        return R.ok(service.planMesList(planMesQueryDTO));
    }

    /**
     * 获取批次生成器类型列表
     *
     * @return 批次生成器类型和名称映射
     */
    @Operation(summary = "获取批次生成器类型列表", description = "获取批次生成器类型列表")
    @GetMapping("/batchGeneratorTypes")
    public R<List<BatchGeneratorTypeDTO>> getBatchGeneratorTypes() {
        return R.ok(batchGeneratorService.getAllBatchGeneratorTypes());
    }

    /**
     * 根据生产计划单号查询生产工单
     *
     * @param planNo 生产计划单号
     * @return R
     */
    @Operation(summary = "根据生产计划单号查询生产工单", description = "根据生产计划单号查询生产工单")
    @GetMapping("/omsLife")
    public R<List<MesTaskEntity>> omsLife(@RequestParam("planNo") String planNo) {
        return R.ok(service.omsLife(planNo));
    }

    /**
     * 根据产品编码和工位编码查询当前用户需要做的工单信息
     * 如果有已经开始的则查询开始的，没有开始的则查询最近需要开始的工单信息
     *
     * @param queryDTO 查询参数
     * @return 当前用户需要做的工单信息
     */
    @Operation(summary = "根据产品编码和工位编码查询当前用户需要做的工单信息", description = "根据产品编码和工位编码查询当前用户需要做的工单信息")
    @PostMapping("/getCurrentWorkByProductAndWorksite")
    public R<MesTaskCurrentWorkVO> getCurrentWorkByProductAndWorksite(@RequestBody MesTaskQueryByProductWorksiteDTO queryDTO) {
        return R.ok(service.getCurrentWorkByProductAndWorksite(queryDTO));
    }

    /**
     * 获取工单状态统计信息
     *
     * @param query 查询条件
     * @return 工单状态统计信息
     */

    @Operation(summary = "获取工单状态统计信息", description = "获取工单状态统计信息")
    @GetMapping("/getCount")
    @PreAuthorize("@pms.hasPermission('mesTask:show')")
    public R<Map<String, Long>> getCount(@ParameterObject MesTaskEntity query) {
        QueryWrapper<MesTaskEntity> autoQueryWrapper = new QueryWrapper<>();
        autoQueryWrapper.lambda()
                .like(StrUtil.isNotBlank(query.getCode()), MesTaskEntity::getCode, query.getCode())
                .like(StrUtil.isNotBlank(query.getMatCode()), MesTaskEntity::getMatCode, query.getMatCode())
                .like(StrUtil.isNotBlank(query.getMatName()), MesTaskEntity::getMatName, query.getMatName())
                .like(StrUtil.isNotBlank(query.getCode()), MesTaskEntity::getCode, query.getCode())
                .eq(StrUtil.isNotBlank(query.getType()), MesTaskEntity::getType, query.getType())
                .orderByDesc(MesTaskEntity::getCreateTime);
        return R.ok(service.list(autoQueryWrapper)
                .stream()
                .collect(Collectors.groupingBy(MesTaskEntity::getStatus, Collectors.counting())));
    }

}
