package com.kitex.mom.mdm.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.tree.Tree;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mdm.entity.MdmSupplierClassEntity;
import com.kitex.mom.mdm.service.MdmSupplierClassService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;

/**
 * 供应商分类表
 *
 * @author kitex
 * @date 2025-03-18 16:46:14
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmSupplierClass")
@Tag(description = "mdmSupplierClass", name = "供应商分类表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmSupplierClassController {

    private final MdmSupplierClassService mdmSupplierClassService;

    /**
     * 分页查询
     *
     * @param page             分页对象
     * @param mdmSupplierClass 供应商分类表
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mdm_mdmSupplierClass_view')")
    public R<Page<MdmSupplierClassEntity>> getMdmSupplierClassPage(@ParameterObject SearchPage page, @ParameterObject MdmSupplierClassEntity mdmSupplierClass) {
        LambdaQueryWrapper<MdmSupplierClassEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(Objects.nonNull(mdmSupplierClass.getParentId()), MdmSupplierClassEntity::getParentId, mdmSupplierClass.getParentId());
        wrapper.eq(StrUtil.isNotBlank(mdmSupplierClass.getCode()), MdmSupplierClassEntity::getCode, mdmSupplierClass.getCode());
        return R.ok(mdmSupplierClassService.page(page.toMpPage(), wrapper));
    }

    /**
     * 供应商分类树形数据
     *
     * @param mdmSupplierClass 供应商分类表
     * @return
     */
    @Operation(summary = "供应商分类树形数据", description = "供应商分类树形数据")
    @GetMapping("/tree")
    @PreAuthorize("@pms.hasPermission('mdm_mdmSupplierClass_view')")
    public R<List<Tree<Long>>> getSupplierClassTree(@ParameterObject MdmSupplierClassEntity mdmSupplierClass) {
        return R.ok(mdmSupplierClassService.getSupplierClassTree(mdmSupplierClass));
    }

    /**
     * 供应商分类列表
     *
     * @return
     */
    @Operation(summary = "供应商分类列表", description = "供应商分类列表")
    @GetMapping("/list")
    public R<List<MdmSupplierClassEntity>> getList() {
        return R.ok(mdmSupplierClassService.list());
    }

    /**
     * 通过id查询供应商分类表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mdm_mdmSupplierClass_view')")
    public R<MdmSupplierClassEntity> getById(@PathVariable("id") Long id) {
        return R.ok(mdmSupplierClassService.getById(id));
    }

    /**
     * 新增供应商分类表
     *
     * @param mdmSupplierClass 供应商分类表
     * @return R
     */
    @Operation(summary = "新增供应商分类表", description = "新增供应商分类表")
    @SysLog("新增供应商分类表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmSupplierClass_add')")
    public R<Boolean> save(@RequestBody MdmSupplierClassEntity mdmSupplierClass) {
        return R.ok(mdmSupplierClassService.save(mdmSupplierClass));
    }

    /**
     * 修改供应商分类表
     *
     * @param mdmSupplierClass 供应商分类表
     * @return R
     */
    @Operation(summary = "修改供应商分类表", description = "修改供应商分类表")
    @SysLog("修改供应商分类表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmSupplierClass_edit')")
    public R<Boolean> updateById(@RequestBody MdmSupplierClassEntity mdmSupplierClass) {
        return R.ok(mdmSupplierClassService.updateById(mdmSupplierClass));
    }

    /**
     * 通过id删除供应商分类表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除供应商分类表", description = "通过id删除供应商分类表")
    @SysLog("通过id删除供应商分类表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmSupplierClass_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(mdmSupplierClassService.removeTreeBatchByIds(CollUtil.toList(ids)));
    }

    /**
     * 导出excel 表格
     *
     * @param mdmSupplierClass 查询条件
     * @param ids              导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mdm_mdmSupplierClass_export')")
    public List<MdmSupplierClassEntity> export(MdmSupplierClassEntity mdmSupplierClass, Long[] ids) {
        return mdmSupplierClassService.list(Wrappers.lambdaQuery(mdmSupplierClass).in(ArrayUtil.isNotEmpty(ids), MdmSupplierClassEntity::getId, ids));
    }
}