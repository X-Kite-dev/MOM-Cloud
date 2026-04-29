package com.kitex.mom.mes.controller;


import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.mom.mes.entity.MesTaskDeviceRecordEntity;
import com.kitex.mom.mes.service.MesTaskDeviceRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 生产报工-设备开机记录
 *
 * @author kitex
 * @since 2025-04-21
 */
@Tag(description = "mesTaskDeviceRecord", name = "生产报工-操作明细")
@RestController
@RequestMapping("/mesTask/device/record")
@RequiredArgsConstructor
public class MesTaskDeviceRecordController {

    private final MesTaskDeviceRecordService service;


    @Operation(summary = "报工记录分页查询", description = "报工记录分页查询")
    @GetMapping("/page")
    public R<IPage<MesTaskDeviceRecordEntity>> page(Page<MesTaskDeviceRecordEntity> pageParam, MesTaskDeviceRecordEntity query) {
        QueryWrapper<MesTaskDeviceRecordEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(query);
        autoQueryWrapper.lambda().orderByDesc(MesTaskDeviceRecordEntity::getCreateTime);
        return R.ok(service.page(pageParam, autoQueryWrapper));
    }


}
