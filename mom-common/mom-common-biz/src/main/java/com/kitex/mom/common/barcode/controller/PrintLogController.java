package com.kitex.mom.common.barcode.controller;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.query.enums.CommonQuery;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.common.barcode.entity.PrintLogEntity;
import com.kitex.mom.common.barcode.service.PrintLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 打印记录
 *
 * @author kitex
 * @date 2023-08-17 16:29:36
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/printLog")
@Tag(description = "printLog", name = "打印记录管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PrintLogController {

    private final PrintLogService printLogService;

    /**
     * 分页查询
     *
     * @param page     分页对象
     * @param printLog 打印记录
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('barcode_printLog_view')")
    public R<Page<PrintLogEntity>> getPrintLogPage(SearchPage page, PrintLogEntity printLog, CommonQuery commonQuery) {
        QueryWrapper<PrintLogEntity> queryWrapper = QueryGenerator.initQueryWrapper(printLog, commonQuery);
        return R.ok(printLogService.page(page.toMpPage(), queryWrapper));
    }

    /**
     * 通过id查询打印记录
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('barcode_printLog_view')")
    public R<PrintLogEntity> getById(@PathVariable("id") Long id) {
        return R.ok(printLogService.getById(id));
    }

    /**
     * 通过id删除打印记录
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除打印记录", description = "通过id删除打印记录")
    @SysLog("通过id删除打印记录")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('barcode_printLog_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(printLogService.removeBatchByIds(CollUtil.toList(ids)));
    }

    @GetMapping("/preview")
    public R<String> preview(@RequestParam("id") Long id) {
        String content = printLogService.preview(id);
        return R.ok(content);
    }

    /**
     * 重新打印
     *
     * @param id
     * @return
     */
    @GetMapping("/reprint")
    public R reprint(Long id) {
        printLogService.reprint(id);
        return R.ok();
    }

    @GetMapping("/previewByUrl")
    @Inner(value = false)
    public String previewByUrl(@RequestParam("id") Long id) {
        return printLogService.preview(id);
    }

    /**
     * 导出excel 表格
     *
     * @param printLog 查询条件
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('barcode_printLog_export')")
    public List<PrintLogEntity> export(PrintLogEntity printLog) {
        return printLogService.list(Wrappers.query(printLog));
    }

}