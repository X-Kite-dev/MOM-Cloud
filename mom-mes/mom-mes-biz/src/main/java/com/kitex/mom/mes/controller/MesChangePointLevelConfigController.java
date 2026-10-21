package com.kitex.mom.mes.controller;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.mom.mes.entity.MesChangePointLevelConfigEntity;
import com.kitex.mom.mes.service.MesChangePointLevelConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(description = "mesChangePointLevelConfig", name = "变化点等级配置")
@RestController
@RequestMapping("/mesChangePointLevelConfig")
@RequiredArgsConstructor
public class MesChangePointLevelConfigController {

    private final MesChangePointLevelConfigService service;

    @Operation(summary = "变化点等级配置分页")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mesChangePointLevelConfig:show')")
    public R<IPage<MesChangePointLevelConfigEntity>> page(Page<MesChangePointLevelConfigEntity> pageParam, MesChangePointLevelConfigEntity query) {
        QueryWrapper<MesChangePointLevelConfigEntity> qw = QueryGenerator.initQueryWrapper(query);
        qw.lambda().orderByDesc(MesChangePointLevelConfigEntity::getCreateTime);
        return R.ok(service.page(pageParam, qw));
    }

    @Operation(summary = "变化点等级配置详情")
    @GetMapping("/getById")
    @PreAuthorize("@pms.hasPermission('mesChangePointLevelConfig:show')")
    public R<MesChangePointLevelConfigEntity> getById(Long id) {
        return R.ok(service.getById(id));
    }

    @Operation(summary = "变化点等级配置设置")
    @PostMapping("/set")
    @PreAuthorize("@pms.hasPermission('mesChangePointLevelConfig:update')")
    public R<Void> set(@RequestBody MesChangePointLevelConfigEntity entity) {
        service.setByConfigCode(entity);
        return R.ok();
    }

    @Operation(summary = "变化点等级配置删除")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mesChangePointLevelConfig:delete')")
    public R<Void> removeById(@RequestBody Long[] ids) {
        service.removeBatchByIds(CollUtil.toList(ids));
        return R.ok();
    }
}
