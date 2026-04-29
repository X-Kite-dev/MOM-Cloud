package com.kitex.mom.mdm.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mdm.entity.MdmDeviceClassEntity;
import com.kitex.mom.mdm.service.MdmDeviceClassService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 工装分类表（设备）
 *
 * @author kitex
 * @date 2025-02-05 10:28:48
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmDeviceClass")
@Tag(description = "mdmDeviceClass", name = "工装分类表（设备）管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmDeviceClassController {

    private final MdmDeviceClassService mdmDeviceClassService;

    /**
     * 分页查询
     *
     * @param page           分页对象
     * @param mdmDeviceClass 工装分类表（设备）
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mdm_mdmDeviceClass_view')")
    public R getMdmDeviceClassPage(@ParameterObject SearchPage page, @ParameterObject MdmDeviceClassEntity mdmDeviceClass) {
        LambdaQueryWrapper<MdmDeviceClassEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(mdmDeviceClass.getCode()), MdmDeviceClassEntity::getCode, mdmDeviceClass.getCode());
        wrapper.like(StrUtil.isNotBlank(mdmDeviceClass.getName()), MdmDeviceClassEntity::getName, mdmDeviceClass.getName());
        return R.ok(mdmDeviceClassService.page(page.toMpPage(), wrapper));
    }

    /**
     * 获取设备分类树形结构
     */
    @Operation(summary = "获取设备分类树形结构", description = "获取设备分类树形结构")
    @GetMapping("/tree")
    public R getMdmDeviceClassPage(@ParameterObject MdmDeviceClassEntity mdmDeviceClass) {
        return R.ok(mdmDeviceClassService.tree(mdmDeviceClass));
    }

    /**
     * 通过id查询工装分类表（设备）
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mdm_mdmDeviceClass_view')")
    public R getById(@PathVariable("id") Long id) {
        return R.ok(mdmDeviceClassService.getById(id));
    }

    /**
     * 查询所有的物料分类
     */
    @Operation(summary = "查询所有的物料分类", description = "查询所有的物料分类")
    @GetMapping("/getList")
    public R getList() {
        return R.ok(mdmDeviceClassService.list());
    }

    /**
     * 新增工装分类表（设备）
     *
     * @param mdmDeviceClass 工装分类表（设备）
     * @return R
     */
    @Operation(summary = "新增工装分类表（设备）", description = "新增工装分类表（设备）")
    @SysLog("新增工装分类表（设备）")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmDeviceClass_add')")
    public R save(@RequestBody MdmDeviceClassEntity mdmDeviceClass) {
        long count = mdmDeviceClassService.count(new LambdaQueryWrapper<MdmDeviceClassEntity>()
                .eq(MdmDeviceClassEntity::getCode, mdmDeviceClass.getCode()));
        if (count > 0) {
            throw new RuntimeException("存在重复设备类型编码！");
        }
        long countName = mdmDeviceClassService.count(new LambdaQueryWrapper<MdmDeviceClassEntity>()
                .eq(MdmDeviceClassEntity::getName, mdmDeviceClass.getName()));
        if (countName > 0) {
            throw new RuntimeException("存在重复设备类型名称！");
        }
        return R.ok(mdmDeviceClassService.save(mdmDeviceClass));
    }

    /**
     * 修改工装分类表（设备）
     *
     * @param mdmDeviceClass 工装分类表（设备）
     * @return R
     */
    @Operation(summary = "修改工装分类表（设备）", description = "修改工装分类表（设备）")
    @SysLog("修改工装分类表（设备）")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmDeviceClass_edit')")
    public R updateById(@RequestBody MdmDeviceClassEntity mdmDeviceClass) {
        long countName = mdmDeviceClassService.count(new LambdaQueryWrapper<MdmDeviceClassEntity>()
                .eq(MdmDeviceClassEntity::getName, mdmDeviceClass.getName())
                .ne(MdmDeviceClassEntity::getId, mdmDeviceClass.getId()));
        if (countName > 0) {
            throw new RuntimeException("存在重复设备类型名称！");
        }
        return R.ok(mdmDeviceClassService.updateById(mdmDeviceClass));
    }

    /**
     * 通过id删除工装分类表（设备）
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除工装分类表（设备）", description = "通过id删除工装分类表（设备）")
    @SysLog("通过id删除工装分类表（设备）")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmDeviceClass_del')")
    public R removeDeviceClassById(@RequestBody Long[] ids) {
        return R.ok(mdmDeviceClassService.removeDeviceClassById(CollUtil.toList(ids)));
    }

    /**
     * 导出excel 表格
     *
     * @param mdmDeviceClass 查询条件
     * @param ids            导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mdm_mdmDeviceClass_export')")
    public List<MdmDeviceClassEntity> export(MdmDeviceClassEntity mdmDeviceClass, Long[] ids) {
        return mdmDeviceClassService.list(Wrappers.lambdaQuery(mdmDeviceClass).in(ArrayUtil.isNotEmpty(ids), MdmDeviceClassEntity::getId, ids));
    }
}