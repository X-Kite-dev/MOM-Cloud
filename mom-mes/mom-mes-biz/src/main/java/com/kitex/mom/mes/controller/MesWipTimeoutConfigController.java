package com.kitex.mom.mes.controller;

import cn.hutool.core.collection.CollUtil;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.mom.mes.entity.MesWipTimeoutConfigEntity;
import com.kitex.mom.mes.service.MesWipTimeoutConfigService;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(description = "mesWipTimeoutConfig", name = "在制超时管控配置")
@RestController
@RequestMapping("/mesWipTimeoutConfig")
@RequiredArgsConstructor
public class MesWipTimeoutConfigController {

    private final MesWipTimeoutConfigService service;
    private final MesActualFactoryAccessService access;

    @Operation(summary = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mesWipTimeoutConfig:show')")
    public R<IPage<MesWipTimeoutConfigEntity>> page(
            Page<MesWipTimeoutConfigEntity> pageParam, MesWipTimeoutConfigEntity query) {
        QueryWrapper<MesWipTimeoutConfigEntity> qw = QueryGenerator.initQueryWrapper(query);
        String factory = access.require("read");
        qw.lambda()
                .eq(MesWipTimeoutConfigEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesWipTimeoutConfigEntity::getFactoryCode, factory)
                .orderByDesc(MesWipTimeoutConfigEntity::getCreateTime);
        return R.ok(service.page(pageParam, qw));
    }

    @Operation(summary = "新增")
    @PostMapping("/add")
    @PreAuthorize("@pms.hasPermission('mesWipTimeoutConfig:add')")
    public R<MesWipTimeoutConfigEntity> add(@RequestBody MesWipTimeoutConfigEntity params) {
        return R.ok(service.saveData(params));
    }

    @Operation(summary = "编辑")
    @PostMapping("/update")
    @PreAuthorize("@pms.hasPermission('mesWipTimeoutConfig:update')")
    public R<Void> update(@RequestBody MesWipTimeoutConfigEntity entity) {
        service.updateData(entity);
        return R.ok();
    }

    @Operation(summary = "详情")
    @GetMapping("/getById")
    @PreAuthorize("@pms.hasPermission('mesWipTimeoutConfig:show')")
    public R<MesWipTimeoutConfigEntity> getById(Long id) {
        return R.ok(service.readData(id));
    }

    @Operation(summary = "删除")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mesWipTimeoutConfig:delete')")
    public R<Void> removeById(@RequestBody Long[] ids) {
        service.removeUnused(CollUtil.toList(ids));
        return R.ok();
    }
}
