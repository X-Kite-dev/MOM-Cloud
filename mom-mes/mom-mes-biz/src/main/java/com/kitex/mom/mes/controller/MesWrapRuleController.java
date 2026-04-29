package com.kitex.mom.mes.controller;


import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.mom.mes.entity.MesWrapRuleEntity;
import com.kitex.mom.mes.service.MesWrapRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 箱码规则管理
 *
 * @author kitex
 * @since 2025-03-25
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesWrapRule")
@Tag(description = "mesWrapRule", name = "箱码规则管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MesWrapRuleController {

    private final MesWrapRuleService service;

    /**
     * 分页查询
     *
     * @param pageParam
     * @param query
     * @return
     */

    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mesWrapRule:show')")
    public R<IPage<MesWrapRuleEntity>> page(Page<MesWrapRuleEntity> pageParam, MesWrapRuleEntity query) {
        QueryWrapper<MesWrapRuleEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(query);
        autoQueryWrapper.lambda().orderByDesc(MesWrapRuleEntity::getCreateTime);
        return R.ok(service.page(pageParam, autoQueryWrapper));
    }

    @Operation(summary = "列表查询", description = "列表查询")
    @GetMapping("/list")
    public R<List<MesWrapRuleEntity>> list(MesWrapRuleEntity query) {
        QueryWrapper<MesWrapRuleEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(query);
        autoQueryWrapper.lambda().eq(MesWrapRuleEntity::getStatus, Boolean.TRUE)
                .orderByDesc(MesWrapRuleEntity::getCreateTime);
        return R.ok(service.list(autoQueryWrapper));
    }


    /**
     * 通过id查询箱码规则管理
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mesWrapRule:show')")
    public R<MesWrapRuleEntity> getById(@PathVariable("id") Long id) {
        return R.ok(service.getById(id));
    }

    /**
     * 新增箱码规则管理
     *
     * @param mesWrapRule 箱码规则管理
     * @return R
     */
    @Operation(summary = "新增箱码规则管理", description = "新增箱码规则管理")
    @SysLog("新增箱码规则管理")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('mesWrapRule:add')")
    public R<Void> save(@RequestBody MesWrapRuleEntity mesWrapRule) {
        service.save(mesWrapRule);
        return R.ok();
    }

    /**
     * 修改箱码规则管理
     *
     * @param mesWrapRule 箱码规则管理
     * @return R
     */
    @Operation(summary = "修改箱码规则管理", description = "修改箱码规则管理")
    @SysLog("修改箱码规则管理")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('mesWrapRule:edit')")
    public R<Void> updateById(@RequestBody MesWrapRuleEntity mesWrapRule) {
        service.updateById(mesWrapRule);
        return R.ok();
    }

    /**
     * 通过id删除箱码规则管理
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除箱码规则管理", description = "通过id删除箱码规则管理")
    @SysLog("通过id删除箱码规则管理")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mesWrapRule:delete')")
    public R<Void> removeById(@RequestBody Long[] ids) {
        service.removeBatchByIds(CollUtil.toList(ids));
        return R.ok();
    }
}