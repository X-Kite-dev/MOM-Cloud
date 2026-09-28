package com.kitex.mom.mes.controller;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.mom.mes.entity.MesTaskReportRecordEntity;
import com.kitex.mom.mes.service.MesTaskReportRecordService;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 生产报工-操作明细
 *
 * @author kitex
 * @since 2025-05-06
 */
@Tag(description = "mesTaskReportRecord", name = "生产报工-操作明细")
@RestController
@RequestMapping("/mesTask/report/record")
@RequiredArgsConstructor
public class MesTaskReportRecordController {

    private final MesTaskReportRecordService service;
    private final MesActualFactoryAccessService factoryAccess;

    @Operation(summary = "生产报工-操作明细", description = "生产报工-操作明细")
    @GetMapping("/queryRecord")
    public R<List<MesTaskReportRecordEntity>> queryRecord(String arrangeCode) {
        return R.ok(service.list(new LambdaQueryWrapper<MesTaskReportRecordEntity>()
                .eq(MesTaskReportRecordEntity::getFactoryCode, factoryAccess.require("read"))
                .eq(MesTaskReportRecordEntity::getArrangeCode, arrangeCode)));
    }


    @Operation(summary = "报工记录分页查询", description = "报工记录分页查询")
    @GetMapping("/page")
    public R<IPage<MesTaskReportRecordEntity>> page(Page<MesTaskReportRecordEntity> pageParam, MesTaskReportRecordEntity query) {
        QueryWrapper<MesTaskReportRecordEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(query);
        autoQueryWrapper.lambda()
                .eq(MesTaskReportRecordEntity::getFactoryCode, factoryAccess.require("read"))
                .orderByDesc(MesTaskReportRecordEntity::getReportTime);
        return R.ok(service.page(pageParam, autoQueryWrapper));
    }


}
