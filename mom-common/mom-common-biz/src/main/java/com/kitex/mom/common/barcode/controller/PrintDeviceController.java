package com.kitex.mom.common.barcode.controller;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.query.enums.CommonQuery;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.mom.common.barcode.dto.req.PrintDeviceReq;
import com.kitex.mom.common.barcode.entity.PrintDeviceEntity;
import com.kitex.mom.common.barcode.service.PrintDeviceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 打印设备
 *
 * @author kitex
 * @date 2023-08-11 10:26:30
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/printDevice")
@Tag(description = "printDevice", name = "打印设备管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PrintDeviceController {

    private final PrintDeviceService printDeviceService;

    /**
     * 分页查询
     *
     * @param page 分页对象
     * @param req  打印设备
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('barcode_printDevice_view')")
    public R<Page<PrintDeviceEntity>> getPrintDevicePage(SearchPage page, PrintDeviceReq req, CommonQuery commonQuery) {
        QueryWrapper<PrintDeviceEntity> queryWrapper = QueryGenerator.initQueryWrapper(req, commonQuery);
        return R.ok(printDeviceService.page(page.toMpPage(), queryWrapper));
    }

    /**
     * 通过id查询打印设备
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('barcode_printDevice_view')")
    public R<PrintDeviceEntity> getById(@PathVariable("id") Long id) {
        return R.ok(printDeviceService.getById(id));
    }

    /**
     * 新增打印设备
     *
     * @param printDevice 打印设备
     * @return R
     */
    @Operation(summary = "新增打印设备", description = "新增打印设备")
    @SysLog("新增打印设备")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('barcode_printDevice_add')")
    public R<Boolean> save(@RequestBody PrintDeviceEntity printDevice) {
        return R.ok(printDeviceService.save(printDevice));
    }

    /**
     * 修改打印设备
     *
     * @param printDevice 打印设备
     * @return R
     */
    @Operation(summary = "修改打印设备", description = "修改打印设备")
    @SysLog("修改打印设备")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('barcode_printDevice_edit')")
    public R<Boolean> updateById(@RequestBody PrintDeviceEntity printDevice) {
        return R.ok(printDeviceService.updateById(printDevice));
    }

    /**
     * 通过id删除打印设备
     *
     * @param ids ${pk.attrName}列表
     * @return R
     */
    @Operation(summary = "通过id删除打印设备", description = "通过id删除打印设备")
    @SysLog("通过id删除打印设备")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('barcode_printDevice_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(printDeviceService.removeBatchByIds(CollUtil.toList(ids)));
    }

    /**
     * 导出excel 表格
     *
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('barcode_printDevice_export')")
    public List<PrintDeviceEntity> export(PrintDeviceReq req, CommonQuery commonQuery) {
        QueryWrapper<PrintDeviceEntity> queryWrapper = QueryGenerator.initQueryWrapper(req, commonQuery);
        return printDeviceService.list(queryWrapper);
    }

}