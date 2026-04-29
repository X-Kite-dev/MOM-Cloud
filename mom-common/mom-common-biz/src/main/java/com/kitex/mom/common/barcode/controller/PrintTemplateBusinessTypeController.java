package com.kitex.mom.common.barcode.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.common.barcode.entity.PrintTemplateBusinessTypeEntity;
import com.kitex.mom.common.barcode.service.PrintTemplateBusinessTypeService;
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
import java.util.stream.Collectors;

/**
 * 打印模板业务类型管理
 *
 * @author kitex
 * @date 2025-08-14 11:09:06
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/printTemplateBusinessType")
@Tag(description = "printTemplateBusinessType", name = "打印模板业务类型管理管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PrintTemplateBusinessTypeController {

    private final PrintTemplateBusinessTypeService printTemplateBusinessTypeService;

    /**
     * 分页查询
     *
     * @param page                      分页对象
     * @param printTemplateBusinessType 打印模板业务类型管理
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('barcode_printTemplateBusinessType_view')")
    public R getPrintTemplateBusinessTypePage(@ParameterObject SearchPage page, @ParameterObject PrintTemplateBusinessTypeEntity printTemplateBusinessType) {
        LambdaQueryWrapper<PrintTemplateBusinessTypeEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(printTemplateBusinessType.getCode()), PrintTemplateBusinessTypeEntity::getCode, printTemplateBusinessType.getCode());
        wrapper.like(StrUtil.isNotBlank(printTemplateBusinessType.getName()), PrintTemplateBusinessTypeEntity::getName, printTemplateBusinessType.getName());
        return R.ok(printTemplateBusinessTypeService.page(page.toMpPage(), wrapper));
    }

    /**
     * 查询各打印模板业务类型管理管理状态的数量
     */
    @Operation(summary = "查询各打印模板业务类型管理状态的数量", description = "查询各打印模板业务类型管理状态的数量")
    @GetMapping("/getCount")
    public R<Map<Boolean, Long>> getPrintTemplateBusinessTypeCount(@ParameterObject PrintTemplateBusinessTypeEntity printTemplate) {
        LambdaQueryWrapper<PrintTemplateBusinessTypeEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper
                .like(StrUtil.isNotBlank(printTemplate.getCode()), PrintTemplateBusinessTypeEntity::getCode, printTemplate.getCode())
                .like(StrUtil.isNotBlank(printTemplate.getName()), PrintTemplateBusinessTypeEntity::getName, printTemplate.getName());
        return R.ok(printTemplateBusinessTypeService.list(queryWrapper)
                .stream()
                .collect(Collectors.groupingBy(PrintTemplateBusinessTypeEntity::getStatus, Collectors.counting())));
    }

    /**
     * 通过id查询打印模板业务类型管理
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('barcode_printTemplateBusinessType_view')")
    public R getById(@PathVariable("id") Long id) {
        return R.ok(printTemplateBusinessTypeService.getById(id));
    }

    /**
     * 根据编码获取详情
     *
     * @param code 编码
     * @return R
     */
    @Operation(summary = "根据编码获取详情", description = "根据编码获取详情")
    @GetMapping("/detail")
    public R<PrintTemplateBusinessTypeEntity> detail(String code) {
        return R.ok(printTemplateBusinessTypeService.getOne(
                Wrappers.<PrintTemplateBusinessTypeEntity>lambdaQuery().eq(PrintTemplateBusinessTypeEntity::getCode, code), false));
    }

    /**
     * 获取打印模板业务类型管理列表
     *
     * @return R
     */
    @Operation(summary = "获取打印模板业务类型管理列表", description = "获取打印模板业务类型管理列表")
    @GetMapping("/list")
    public R<List<PrintTemplateBusinessTypeEntity>> list(@ParameterObject PrintTemplateBusinessTypeEntity printTemplate) {
        return R.ok(printTemplateBusinessTypeService.list(
                Wrappers.<PrintTemplateBusinessTypeEntity>lambdaQuery()
                        .and(StrUtil.isNotBlank(printTemplate.getName()), wrapper -> wrapper
                                .like(PrintTemplateBusinessTypeEntity::getCode, printTemplate.getName())
                                .or()
                                .like(PrintTemplateBusinessTypeEntity::getName, printTemplate.getName()))
                        .eq(PrintTemplateBusinessTypeEntity::getStatus, Boolean.TRUE)));
    }

    /**
     * 新增打印模板业务类型管理
     *
     * @param printTemplateBusinessType 打印模板业务类型管理
     * @return R
     */
    @Operation(summary = "新增打印模板业务类型管理", description = "新增打印模板业务类型管理")
    @SysLog("新增打印模板业务类型管理")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('barcode_printTemplateBusinessType_add')")
    public R save(@RequestBody PrintTemplateBusinessTypeEntity printTemplateBusinessType) {
        return R.ok(printTemplateBusinessTypeService.save(printTemplateBusinessType));
    }

    /**
     * 修改打印模板业务类型管理
     *
     * @param printTemplateBusinessType 打印模板业务类型管理
     * @return R
     */
    @Operation(summary = "修改打印模板业务类型管理", description = "修改打印模板业务类型管理")
    @SysLog("修改打印模板业务类型管理")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('barcode_printTemplateBusinessType_edit')")
    public R updateById(@RequestBody PrintTemplateBusinessTypeEntity printTemplateBusinessType) {
        return R.ok(printTemplateBusinessTypeService.updateById(printTemplateBusinessType));
    }

    /**
     * 通过id删除打印模板业务类型管理
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除打印模板业务类型管理", description = "通过id删除打印模板业务类型管理")
    @SysLog("通过id删除打印模板业务类型管理")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('barcode_printTemplateBusinessType_del')")
    public R removeById(@RequestBody Long[] ids) {
        return R.ok(printTemplateBusinessTypeService.removeBatchByIds(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param printTemplateBusinessType 查询条件
     * @param ids                       导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('barcode_printTemplateBusinessType_export')")
    public List<PrintTemplateBusinessTypeEntity> export(PrintTemplateBusinessTypeEntity printTemplateBusinessType, Long[] ids) {
        return printTemplateBusinessTypeService.list(Wrappers.lambdaQuery(printTemplateBusinessType).in(ArrayUtil.isNotEmpty(ids), PrintTemplateBusinessTypeEntity::getId, ids));
    }
}