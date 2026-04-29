package com.kitex.mom.common.barcode.controller;

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
import com.kitex.mom.common.barcode.entity.BarcodeCheckLogEntity;
import com.kitex.mom.common.barcode.service.BarcodeCheckLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 条码校验记录
 *
 * @author kitex
 * @date 2023-09-01 14:45:49
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/barcodeCheckLog")
@Tag(description = "barcodeCheckLog", name = "条码校验记录管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class BarcodeCheckLogController {

    private final BarcodeCheckLogService barcodeCheckLogService;

    /**
     * 分页查询
     *
     * @param page            分页对象
     * @param barcodeCheckLog 条码校验记录
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('barcode_barcodeCheckLog_view')")
    public R<Page<BarcodeCheckLogEntity>> Page(SearchPage page, BarcodeCheckLogEntity barcodeCheckLog) {
        LambdaQueryWrapper<BarcodeCheckLogEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.like(StrUtil.isNotBlank(barcodeCheckLog.getBarcodeCode()), BarcodeCheckLogEntity::getBarcodeCode, barcodeCheckLog.getBarcodeCode());
        queryWrapper.like(StrUtil.isNotBlank(barcodeCheckLog.getBarcodeName()), BarcodeCheckLogEntity::getBarcodeName, barcodeCheckLog.getBarcodeName());
        queryWrapper.orderByDesc(BarcodeCheckLogEntity::getCreateTime);
        return R.ok(barcodeCheckLogService.page(page.toMpPage(), queryWrapper));
    }

    /**
     * 通过id查询条码校验记录
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('barcode_barcodeCheckLog_view')")
    public R<BarcodeCheckLogEntity> getById(@PathVariable("id") Long id) {
        return R.ok(barcodeCheckLogService.getById(id));
    }

    /**
     * 通过id删除条码校验记录
     *
     * @param ids id
     * @return R
     */
    @Operation(summary = "通过id删除条码校验记录", description = "通过id删除条码校验记录")
    @SysLog("通过id删除条码校验记录")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('barcode_barcodeCheckLog_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(barcodeCheckLogService.removeBatchByIds(CollUtil.toList(ids)));
    }

    /**
     * 导出excel 表格
     *
     * @param barcodeCheckLog 查询条件
     * @param ids             导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('barcode_barcodeCheckLog_export')")
    public List<BarcodeCheckLogEntity> export(BarcodeCheckLogEntity barcodeCheckLog, Long[] ids) {
        return barcodeCheckLogService.list(Wrappers.lambdaQuery(barcodeCheckLog).in(ArrayUtil.isNotEmpty(ids), BarcodeCheckLogEntity::getId, ids));
    }

}
