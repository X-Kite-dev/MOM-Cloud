package com.kitex.mom.common.barcode.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.query.enums.CommonQuery;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.mom.common.barcode.entity.PrintTemplateEntity;
import com.kitex.mom.common.barcode.service.PrintTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 打印模板
 *
 * @author kitex
 * @date 2023-08-17 16:26:53
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/printTemplate")
@Tag(description = "printTemplate", name = "打印模板管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PrintTemplateController {

    private final PrintTemplateService printTemplateService;

    /**
     * 分页查询
     *
     * @param page          分页对象
     * @param printTemplate 打印模板
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('barcode_printTemplate_view')")
    public R<Page<PrintTemplateEntity>> getPrintTemplatePage(@ParameterObject SearchPage page, @ParameterObject PrintTemplateEntity printTemplate) {
        LambdaQueryWrapper<PrintTemplateEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper
                .eq(Objects.nonNull(printTemplate.getStatus()), PrintTemplateEntity::getStatus, printTemplate.getStatus())
                .like(StrUtil.isNotBlank(printTemplate.getBusinessTypeCode()), PrintTemplateEntity::getBusinessTypeCode, printTemplate.getBusinessTypeCode())
                .like(StrUtil.isNotBlank(printTemplate.getCode()), PrintTemplateEntity::getCode, printTemplate.getCode())
                .like(StrUtil.isNotBlank(printTemplate.getName()), PrintTemplateEntity::getName, printTemplate.getName())
                .orderByDesc(PrintTemplateEntity::getCreateTime);
        return R.ok(printTemplateService.page(page.toMpPage(), queryWrapper));
    }

    /**
     * 查询各模板管理状态的数量
     */
    @Operation(summary = "查询各模板管理状态的数量", description = "查询各模板管理状态的数量")
    @GetMapping("/getCount")
    public R<Map<Boolean, Long>> getPrintTemplatePage(@ParameterObject PrintTemplateEntity printTemplate) {
        LambdaQueryWrapper<PrintTemplateEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper
                .like(StrUtil.isNotBlank(printTemplate.getBusinessTypeCode()), PrintTemplateEntity::getBusinessTypeCode, printTemplate.getBusinessTypeCode())
                .like(StrUtil.isNotBlank(printTemplate.getCode()), PrintTemplateEntity::getCode, printTemplate.getCode())
                .like(StrUtil.isNotBlank(printTemplate.getName()), PrintTemplateEntity::getName, printTemplate.getName());
        return R.ok(printTemplateService.list(queryWrapper)
                .stream()
                .collect(Collectors.groupingBy(PrintTemplateEntity::getStatus, Collectors.counting())));
    }

    @Operation(summary = "列表查询", description = "列表查询")
    @GetMapping("/list")
    public R<List<PrintTemplateEntity>> list(PrintTemplateEntity printTemplate, CommonQuery commonQuery) {
        QueryWrapper<PrintTemplateEntity> queryWrapper = QueryGenerator.initQueryWrapper(printTemplate, commonQuery);
        return R.ok(printTemplateService.list(queryWrapper));
    }

    /**
     * 根据编码获取详情
     *
     * @param code 编码
     * @return R
     */
    @Operation(summary = "根据编码获取详情", description = "根据编码获取详情")
    @GetMapping("/details")
    public R<PrintTemplateEntity> details(String code) {
        return R.ok(printTemplateService
                .getOne(Wrappers.<PrintTemplateEntity>lambdaQuery().eq(PrintTemplateEntity::getCode, code)));
    }

    /**
     * 通过id查询打印模板
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('barcode_printTemplate_view')")
    public R<PrintTemplateEntity> getById(@PathVariable("id") Long id) {
        return R.ok(printTemplateService.getById(id));
    }

    /**
     * 新增打印模板
     *
     * @param printTemplate 打印模板
     * @return R
     */
    @Operation(summary = "新增打印模板", description = "新增打印模板")
    @SysLog("新增打印模板")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('barcode_printTemplate_add')")
    public R<Boolean> save(@RequestBody PrintTemplateEntity printTemplate) {
        return R.ok(printTemplateService.save(printTemplate));
    }

    /**
     * 修改打印模板
     *
     * @param printTemplate 打印模板
     * @return R
     */
    @Operation(summary = "修改打印模板", description = "修改打印模板")
    @SysLog("修改打印模板")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('barcode_printTemplate_edit')")
    public R<Boolean> updateById(@RequestBody PrintTemplateEntity printTemplate) {
        return R.ok(printTemplateService.updateById(printTemplate));
    }

    /**
     * 通过id删除打印模板
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除打印模板", description = "通过id删除打印模板")
    @SysLog("通过id删除打印模板")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('barcode_printTemplate_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(printTemplateService.removeBatchByIds(CollUtil.toList(ids)));
    }

    /**
     * 模板预览
     *
     * @param code
     * @return
     */
    @GetMapping("/preview")
    public R<String> preview(String code) {
        return R.ok(printTemplateService.preview(code), null);
    }

    /**
     * 导出excel 表格
     *
     * @param printTemplate 查询条件
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('barcode_printTemplate_export')")
    public List<PrintTemplateEntity> export(PrintTemplateEntity printTemplate) {
        return printTemplateService.list(Wrappers.query(printTemplate));
    }

}
