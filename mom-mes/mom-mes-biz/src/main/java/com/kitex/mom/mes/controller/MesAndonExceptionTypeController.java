package com.kitex.mom.mes.controller;


import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.entity.MesAndonExceptionTypeEntity;
import com.kitex.mom.mes.service.MesAndonExceptionTypeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 安灯异常类型配置
 *
 * @author kitex
 * @date 2025-02-18 15:27:53
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesAndonExceptionType")
@Tag(description = "mesAndonExceptionType", name = "安灯异常类型配置管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MesAndonExceptionTypeController {

    private final MesAndonExceptionTypeService service;

    /**
     * 分页查询
     *
     * @param pageParam
     * @param query
     * @return
     */

    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('andonExceptionType:show')")
    public R<IPage<MesAndonExceptionTypeEntity>> page(Page<MesAndonExceptionTypeEntity> pageParam, MesAndonExceptionTypeEntity query) {
        QueryWrapper<MesAndonExceptionTypeEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(query);
        autoQueryWrapper.lambda().orderByDesc(MesAndonExceptionTypeEntity::getCreateTime);
        return R.ok(service.page(pageParam, autoQueryWrapper));
    }

    /**
     * 查询列表
     *
     * @param query
     * @return
     */

    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/list")
    public R<List<MesAndonExceptionTypeEntity>> list(MesAndonExceptionTypeEntity query) {
        QueryWrapper<MesAndonExceptionTypeEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(query);
        autoQueryWrapper.lambda().orderByDesc(MesAndonExceptionTypeEntity::getCreateTime);
        return R.ok(service.list(autoQueryWrapper));
    }


    /**
     * 通过id查询安灯异常类型配置
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('andonExceptionType:show')")
    public R getById(@PathVariable("id") Long id) {
        return R.ok(service.getById(id));
    }

    /**
     * 新增安灯异常类型配置
     *
     * @param mesAndonExceptionType 安灯异常类型配置
     * @return R
     */
    @Operation(summary = "新增安灯异常类型配置", description = "新增安灯异常类型配置")
    @SysLog("新增安灯异常类型配置")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('andonExceptionType:add')")
    public R<Void> save(@RequestBody MesAndonExceptionTypeEntity mesAndonExceptionType) {
        mesAndonExceptionType.setCreateByName(SecurityUtils.getUser().getName());
        service.save(mesAndonExceptionType);
        return R.ok();
    }

    /**
     * 修改安灯异常类型配置
     *
     * @param mesAndonExceptionType 安灯异常类型配置
     * @return R
     */
    @Operation(summary = "修改安灯异常类型配置", description = "修改安灯异常类型配置")
    @SysLog("修改安灯异常类型配置")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('andonExceptionType:edit')")
    public R<Void> updateById(@RequestBody MesAndonExceptionTypeEntity mesAndonExceptionType) {
        service.updateById(mesAndonExceptionType);
        return R.ok();
    }

    /**
     * 通过id删除安灯异常类型配置
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除安灯异常类型配置", description = "通过id删除安灯异常类型配置")
    @SysLog("通过id删除安灯异常类型配置")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('andonExceptionType:delete')")
    public R<Void> removeById(@RequestBody Long[] ids) {
        service.removeBatchByIds(CollUtil.toList(ids));
        return R.ok();
    }

}