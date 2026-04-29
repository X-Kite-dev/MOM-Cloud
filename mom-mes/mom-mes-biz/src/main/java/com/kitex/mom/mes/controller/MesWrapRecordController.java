package com.kitex.mom.mes.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.mom.mes.entity.MesWrapRecordEntity;
import com.kitex.mom.mes.service.MesWrapRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 包装装箱记录
 *
 * @author kitex
 * @since 2025-04-01
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesWrapRecord")
@Tag(description = "mesWrapRecord", name = "包装装箱记录")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MesWrapRecordController {

    private final MesWrapRecordService service;

    /**
     * 分页查询
     *
     * @param pageParam
     * @param query
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    public R<IPage<MesWrapRecordEntity>> page(Page<MesWrapRecordEntity> pageParam, MesWrapRecordEntity query) {
        QueryWrapper<MesWrapRecordEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(query);
        autoQueryWrapper.lambda().orderByDesc(MesWrapRecordEntity::getCreateTime);
        return R.ok(service.page(pageParam, autoQueryWrapper));
    }

    /**
     * 数量
     *
     * @param query
     * @return
     */
    @Operation(summary = "数量", description = "数量")
    @GetMapping("/count")
    public R<Long> getCount(MesWrapRecordEntity query) {
        QueryWrapper<MesWrapRecordEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(query);
        autoQueryWrapper.lambda().orderByDesc(MesWrapRecordEntity::getCreateTime);
        return R.ok(service.count(autoQueryWrapper));
    }


    /**
     * 通过id删除
     *
     * @param id id列表
     * @return R
     */
    @Operation(summary = "通过id删除", description = "通过id删除")
    @SysLog("通过id删除")
    @DeleteMapping
    public R<Void> removeById(Long id) {
        service.removeData(id);
        return R.ok();
    }
} 