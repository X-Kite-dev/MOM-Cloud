package com.kitex.mom.mes.controller;


import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.mom.mes.dto.MesAndonHandleConfigVO;
import com.kitex.mom.mes.entity.MesAndonHandleConfigEntity;
import com.kitex.mom.mes.service.MesAndonHandleConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 安灯异常处理配置
 *
 * @author kitex
 * @date 2025-02-18 15:27:53
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesAndonHandleConfig")
@Tag(description = "mesAndonHandleConfig", name = "安灯异常处理配置")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MesAndonHandleConfigController {

    private final MesAndonHandleConfigService service;

    /**
     * 分页查询
     *
     * @param pageParam
     * @param query
     * @return
     */

    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('andonHandleConfig:show')")
    public R<IPage<MesAndonHandleConfigEntity>> page(Page<MesAndonHandleConfigEntity> pageParam, MesAndonHandleConfigEntity query) {
        QueryWrapper<MesAndonHandleConfigEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(query);
        autoQueryWrapper.lambda().orderByDesc(MesAndonHandleConfigEntity::getCreateTime);
        return R.ok(service.page(pageParam, autoQueryWrapper));
    }


    /**
     * 通过id查询安灯异常处理配置
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('andonHandleConfig:show')")
    public R<MesAndonHandleConfigVO> getById(@PathVariable("id") Long id) {
        return R.ok(service.getConfig(id));
    }

    /**
     * 新增安灯异常处理配置
     *
     * @param mesAndonHandleConfig 安灯异常类型配置
     * @return R
     */
    @Operation(summary = "新增安灯异常处理配置", description = "新增安灯异常处理配置")
    @SysLog("新增安灯异常类型配置")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('andonHandleConfig:add')")
    public R<Void> save(@RequestBody MesAndonHandleConfigVO mesAndonHandleConfig) {
        service.saveData(mesAndonHandleConfig);
        return R.ok();
    }

    /**
     * 修改安灯异常类型配置
     *
     * @param mesAndonHandleConfig 安灯异常类型配置
     * @return R
     */
    @Operation(summary = "修改安灯异常处理配置", description = "修改安灯异常处理配置")
    @SysLog("修改安灯异常类型配置")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('andonHandleConfig:edit')")
    public R<Void> updateById(@RequestBody MesAndonHandleConfigVO mesAndonHandleConfig) {
        service.updateConfig(mesAndonHandleConfig);
        return R.ok();
    }

    /**
     * 通过id删除安灯异常类型配置
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除安灯异常处理配置", description = "通过id删除安灯异常处理配置")
    @SysLog("通过id删除安灯异常类型配置")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('andonHandleConfig:delete')")
    public R<Void> removeById(@RequestBody Long[] ids) {
        service.removeBatchByIds(CollUtil.toList(ids));
        return R.ok();
    }

}