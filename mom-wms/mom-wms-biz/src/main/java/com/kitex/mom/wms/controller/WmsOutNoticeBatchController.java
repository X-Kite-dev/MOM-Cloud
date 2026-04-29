package com.kitex.mom.wms.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.WmsInNoticeQueryDTO;
import com.kitex.mom.wms.entity.WmsOutNoticeBatchEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import com.kitex.mom.wms.service.WmsOutNoticeBatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 批量出库表
 *
 * @author kitex
 * @date 2025-04-09 16:04:44
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsOutNoticeBatch")
@Tag(description = "wmsOutNoticeBatch", name = "批量出库表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsOutNoticeBatchController {

    private final WmsOutNoticeBatchService wmsOutNoticeBatchService;

    /**
     * 分页查询
     *
     * @param page              分页对象
     * @param wmsOutNoticeBatch 批量出库表
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    public R<Page<WmsOutNoticeBatchEntity>> getWmsOutNoticeBatchPage(@ParameterObject SearchPage page, @ParameterObject WmsOutNoticeBatchEntity wmsOutNoticeBatch) {
        LambdaQueryWrapper<WmsOutNoticeBatchEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(wmsOutNoticeBatch.getBillCode()), WmsOutNoticeBatchEntity::getBillCode, wmsOutNoticeBatch.getBillCode());
        return R.ok(wmsOutNoticeBatchService.page(page.toMpPage(), wrapper));
    }

    /**
     * 分页查询统计
     */
    @Operation(summary = "分页查询统计", description = "分页查询统计")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject WmsOutNoticeBatchEntity wmsOutNoticeBatch) {
        LambdaQueryWrapper<WmsOutNoticeBatchEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(wmsOutNoticeBatch.getBillCode()), WmsOutNoticeBatchEntity::getBillCode, wmsOutNoticeBatch.getBillCode());
        return R.ok(wmsOutNoticeBatchService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(WmsOutNoticeBatchEntity::getState, Collectors.counting())));
    }


    /**
     * 通过id查询批量出库表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    public R<WmsOutNoticeBatchEntity> getNoticeBatchById(@PathVariable("id") Long id) {
        return R.ok(wmsOutNoticeBatchService.getNoticeBatchById(id));
    }

    /**
     * 新增批量出库表
     *
     * @param wmsOutNoticeBatch 批量出库表
     * @return R
     */
    @Operation(summary = "新增批量出库表", description = "新增批量出库表")
    @SysLog("新增批量出库表")
    @PostMapping
    public R saveNoticeBatch(@RequestBody WmsOutNoticeBatchEntity wmsOutNoticeBatch) {
        return R.ok(wmsOutNoticeBatchService.saveNoticeBatch(wmsOutNoticeBatch));
    }

    /**
     * 修改批量出库表
     *
     * @param wmsOutNoticeBatch 批量出库表
     * @return R
     */
    @Operation(summary = "修改批量出库表", description = "修改批量出库表")
    @SysLog("修改批量出库表")
    @PutMapping
    public R updateNoticeBatchById(@RequestBody WmsOutNoticeBatchEntity wmsOutNoticeBatch) {
        return R.ok(wmsOutNoticeBatchService.updateNoticeBatchById(wmsOutNoticeBatch));
    }

    /**
     * 通过id删除批量出库表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除批量出库表", description = "通过id删除批量出库表")
    @SysLog("通过id删除批量出库表")
    @DeleteMapping
    public R removeNoticeBatchById(@RequestBody Long[] ids) {
        return R.ok(wmsOutNoticeBatchService.removeNoticeBatchById(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param wmsOutNoticeBatch 查询条件
     * @param ids               导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    public List<WmsOutNoticeBatchEntity> export(WmsOutNoticeBatchEntity wmsOutNoticeBatch, Long[] ids) {
        return wmsOutNoticeBatchService.list(Wrappers.lambdaQuery(wmsOutNoticeBatch).in(ArrayUtil.isNotEmpty(ids), WmsOutNoticeBatchEntity::getId, ids));
    }


    /**
     * 分页查询所有出库通知单
     */
    @Operation(summary = "分页查询所有出库通知单", description = "分页查询所有出库通知单")
    @GetMapping("/getNoticePage")
    public R<Page<WmsOutNoticeEntity>> getNoticePage(SearchPage page, WmsInNoticeQueryDTO queryDTO) {
        return R.ok(wmsOutNoticeBatchService.selectNoticePage(page, queryDTO));
    }

}