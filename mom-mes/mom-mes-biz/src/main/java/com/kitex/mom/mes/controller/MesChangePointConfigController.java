package com.kitex.mom.mes.controller;


import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.mom.mes.entity.MesChangePointConfigEntity;
import com.kitex.mom.mes.service.MesChangePointConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 变化点配置
 *
 * @author kitex
 * @since 2025-05-26
 */
@Tag(description = "mesChangePointConfig", name = "变化点配置")
@RestController
@RequestMapping("/mesChangePointConfig")
@RequiredArgsConstructor
public class MesChangePointConfigController {

    private final MesChangePointConfigService service;


    @Operation(summary = "变化点配置分页", description = "变化点配置分页")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mesChangePointConfig:show')")
    public R<IPage<MesChangePointConfigEntity>> page(Page<MesChangePointConfigEntity> pageParam, MesChangePointConfigEntity query) {
        QueryWrapper<MesChangePointConfigEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(query);
        autoQueryWrapper.lambda().orderByDesc(MesChangePointConfigEntity::getCreateTime);
        return R.ok(service.page(pageParam, autoQueryWrapper));
    }

    @Operation(summary = "变化点配置接收", description = "变化点配置接收")
    @PostMapping("/add")
    public R<Void> add(@RequestBody MesChangePointConfigEntity params) {
        service.save(params);
        return R.ok();
    }

    @Operation(summary = "变化点配置编辑", description = "变化点配置编辑")
    @PostMapping("/update")
    @PreAuthorize("@pms.hasPermission('mesChangePointConfig:update')")
    public R<Void> update(@RequestBody MesChangePointConfigEntity mesChangePointConfig) {
        service.updateById(mesChangePointConfig);
        return R.ok();
    }

    @Operation(summary = "变化点配置编辑", description = "变化点配置编辑")
    @PostMapping("/set")
    @PreAuthorize("@pms.hasPermission('mesChangePointConfig:update')")
    public R<Void> set(@RequestBody MesChangePointConfigEntity mesChangePointConfig) {
        service.set(mesChangePointConfig);
        return R.ok();
    }

    @Operation(summary = "变化点配置编辑", description = "变化点配置编辑")
    @GetMapping("/getById")
    @PreAuthorize("@pms.hasPermission('mesChangePointConfig:show')")
    public R<MesChangePointConfigEntity> getById(Long id) {
        return R.ok(service.getById(id));
    }

    @Operation(summary = "通过id删除", description = "通过id删除")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mesChangePointConfig:delete')")
    public R<Void> removeById(@RequestBody Long[] ids) {
        service.removeBatchByIds(CollUtil.toList(ids));
        return R.ok();
    }
}
