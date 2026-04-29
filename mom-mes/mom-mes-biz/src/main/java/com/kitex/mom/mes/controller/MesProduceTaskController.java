package com.kitex.mom.mes.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.mom.mes.dto.GanttResultDTO;
import com.kitex.mom.mes.dto.RouteProcessDTO;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.service.MesProduceTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 排产发布
 *
 * @author kitex
 * @since 2025-05-06
 */
@Tag(description = "mesProduceTask", name = "排产发布")
@RestController
@RequestMapping("/mesProduceTask")
@RequiredArgsConstructor
public class MesProduceTaskController {

    private final MesProduceTaskService service;


    @Operation(summary = "排产发布分页", description = "排产发布分页")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mesProduceTask:show')")
    public R<IPage<MesProduceTaskEntity>> page(Page<MesProduceTaskEntity> pageParam, MesProduceTaskEntity query) {
        QueryWrapper<MesProduceTaskEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(query);
        autoQueryWrapper.lambda().orderByDesc(MesProduceTaskEntity::getCreateTime);
        return R.ok(service.page(pageParam, autoQueryWrapper));
    }

    @Operation(summary = "排产发布列表", description = "排产发布列表")
    @GetMapping("/list")
    @PreAuthorize("@pms.hasPermission('mesProduceTask:show')")
    public R<List<MesProduceTaskEntity>> queryList(MesProduceTaskEntity query) {
        return R.ok(service.queryList(query));
    }

    @Operation(summary = "排产发布列表", description = "排产发布列表")
    @GetMapping("/processList")
    public R<List<RouteProcessDTO>> queryProcessList(String taskCode) {
        return R.ok(service.queryProcessList(taskCode));
    }


    @Operation(summary = "查询数量", description = "查询数量")
    @GetMapping("/getCount")
    @PreAuthorize("@pms.hasPermission('mesProduceTask:show')")
    public R<Map<String, Long>> getCount(MesProduceTaskEntity query) {
        QueryWrapper<MesProduceTaskEntity> autoQueryWrapper = new QueryWrapper<>();
        autoQueryWrapper.lambda()
                .like(StrUtil.isNotBlank(query.getCode()), MesProduceTaskEntity::getCode, query.getCode())
                .like(StrUtil.isNotBlank(query.getMatCode()), MesProduceTaskEntity::getMatCode, query.getMatCode())
                .like(StrUtil.isNotBlank(query.getMatName()), MesProduceTaskEntity::getMatName, query.getMatName())
                .like(StrUtil.isNotBlank(query.getTaskCode()), MesProduceTaskEntity::getTaskCode, query.getTaskCode())
                .eq(StrUtil.isNotBlank(query.getReportType()), MesProduceTaskEntity::getReportType, query.getReportType())
                .orderByDesc(MesProduceTaskEntity::getCreateTime);
        return R.ok(service.list(autoQueryWrapper)
                .stream()
                .collect(Collectors.groupingBy(MesProduceTaskEntity::getStatus, Collectors.counting())));
    }

    @Operation(summary = "查询详情", description = "查询详情")
    @GetMapping("/findById")
    @PreAuthorize("@pms.hasPermission('mesProduceTask:show')")
    public R<MesProduceTaskEntity> findById(Long id) {
        return R.ok(service.getById(id));
    }

    @Operation(summary = "排产发布甘特图", description = "排产发布甘特图")
    @GetMapping("/getGanttList")
    @PreAuthorize("@pms.hasPermission('mesProduceTask:show')")
    public R<GanttResultDTO> getGanttList(MesProduceTaskEntity query) {
        return R.ok(service.getGanttList(query));
    }


}
