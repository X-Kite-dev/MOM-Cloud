package com.kitex.mom.mdm.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.tree.Tree;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.excel.annotation.RequestExcel;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.common.barcode.dto.req.PrintReq;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.service.MdmWarehouseLocationService;
import com.kitex.mom.mdm.vo.excel.MdmWarehouseLocationExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;

/**
 * 库位
 *
 * @author kitex
 * @date 2024-12-13 18:20:13
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmWarehouseLocation")
@Tag(description = "mdmWarehouseLocation", name = "库位管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmWarehouseLocationController {

    private final MdmWarehouseLocationService mdmWarehouseLocationService;

    /**
     * 分页查询
     *
     * @param page                 分页对象
     * @param mdmWarehouseLocation 库位
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mdm_mdmWarehouseLocation_view')")
    public R<Page<MdmWarehouseLocationEntity>> getMdmWarehouseLocationPage(@ParameterObject Page page,
                                                                           @ParameterObject MdmWarehouseLocationEntity mdmWarehouseLocation) {
        if (ObjUtil.isNotEmpty(mdmWarehouseLocation.getFactoryCode())) {
            // 跳过工厂验证
            FactoryContextHolder.setFactorySkip();
        }
        QueryWrapper<MdmWarehouseLocationEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().eq(StrUtil.isNotBlank(mdmWarehouseLocation.getWarehouseCode()), MdmWarehouseLocationEntity::getWarehouseCode, mdmWarehouseLocation.getWarehouseCode())
                .eq(StrUtil.isNotBlank(mdmWarehouseLocation.getWarehouseName()), MdmWarehouseLocationEntity::getWarehouseName, mdmWarehouseLocation.getWarehouseName())
                .eq(ObjUtil.isNotEmpty(mdmWarehouseLocation.getParentId()), MdmWarehouseLocationEntity::getParentId, mdmWarehouseLocation.getParentId())
                .eq(ObjUtil.isNotEmpty(mdmWarehouseLocation.getStatus()), MdmWarehouseLocationEntity::getStatus, mdmWarehouseLocation.getStatus())
                .eq(StrUtil.isNotBlank(mdmWarehouseLocation.getLocationCode()), MdmWarehouseLocationEntity::getLocationCode, mdmWarehouseLocation.getLocationCode())
                .eq(StrUtil.isNotBlank(mdmWarehouseLocation.getLocationName()), MdmWarehouseLocationEntity::getLocationName, mdmWarehouseLocation.getLocationName())
                .orderByAsc(MdmWarehouseLocationEntity::getSort);
        return R.ok(mdmWarehouseLocationService.page(page, wrapper));
    }

    /**
     * 获取库位树形数据-懒加载
     *
     * @return 库位树形数据
     */
    @Operation(summary = "获取库位树形数据-懒加载", description = "获取库位树形数据-懒加载")
    @GetMapping("/tree")
    @PreAuthorize("@pms.hasPermission('mdm_mdmWarehouseLocation_view')")
    public R<List<Tree<Long>>> getTreeList(MdmWarehouseLocationEntity params) {
        return R.ok(mdmWarehouseLocationService.getTreeList(params));
    }

    /**
     * 获取库位树形数据-全加载
     *
     * @return 库位树形数据
     */
    @Operation(summary = "获取库位树形数据-全加载", description = "获取库位树形数据-全加载")
    @GetMapping("/treeAll")
    @PreAuthorize("@pms.hasPermission('mdm_mdmWarehouseLocation_view')")
    public R<List<Tree<Long>>> getTreeAllList(MdmWarehouseLocationEntity params) {
        return R.ok(mdmWarehouseLocationService.getTreeAllList(params));
    }

    /**
     * 通过条件查询库位
     *
     * @param mdmWarehouseLocation 查询条件
     * @return R 对象列表
     */
    @Operation(summary = "通过条件查询", description = "通过条件查询对象")
    @GetMapping("/details")
    @PreAuthorize("@pms.hasPermission('mdm_mdmWarehouseLocation_view')")
    public R<List<MdmWarehouseLocationEntity>> getDetails(@ParameterObject MdmWarehouseLocationEntity mdmWarehouseLocation) {
        if (ObjUtil.isNotEmpty(mdmWarehouseLocation.getSkipFactory())) {
            FactoryContextHolder.setFactorySkip();
        }
        return R.ok(mdmWarehouseLocationService.list(Wrappers.query(mdmWarehouseLocation)));
    }

    /**
     * 内部布局清单不允许跨租户、跨工厂或不限定仓库查询。
     */
    @Inner
    @GetMapping("/layoutLocations")
    public R<List<MdmWarehouseLocationEntity>> layoutLocations(@RequestParam String warehouseCode) {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        String warehouse = StrUtil.trim(warehouseCode);
        if (tenant == null || StrUtil.isBlank(factory) || StrUtil.isBlank(warehouse)
                || warehouse.contains("*") || Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())) {
            throw new CheckedException("库位图查询必须指定当前租户、工厂和仓库");
        }
        List<MdmWarehouseLocationEntity> rows = mdmWarehouseLocationService.list(
                new LambdaQueryWrapper<MdmWarehouseLocationEntity>()
                        .eq(MdmWarehouseLocationEntity::getTenantId, tenant)
                        .eq(MdmWarehouseLocationEntity::getFactoryCode, factory)
                        .eq(MdmWarehouseLocationEntity::getWarehouseCode, warehouse)
                        .orderByAsc(MdmWarehouseLocationEntity::getSort)
                        .last("LIMIT 2001"));
        if (rows == null || rows.size() > 2000 || rows.stream().anyMatch(row -> row == null
                || !Objects.equals(tenant, row.getTenantId())
                || !Objects.equals(factory, row.getFactoryCode())
                || !Objects.equals(warehouse, row.getWarehouseCode()))) {
            throw new CheckedException("库位图主数据读取不完整或范围不一致");
        }
        return R.ok(rows);
    }

    /**
     * 通过编码查询库位
     *
     * @param code 库位编码
     * @return R 对象
     */
    @Operation(summary = "通过编码查询库位", description = "通过编码查询库位")
    @GetMapping("/getByCode")
    public R<MdmWarehouseLocationEntity> getByCode(@RequestParam String code) {
        List<MdmWarehouseLocationEntity> list = mdmWarehouseLocationService.list(new LambdaQueryWrapper<MdmWarehouseLocationEntity>().eq(MdmWarehouseLocationEntity::getLocationCode, code));
        return R.ok(list.size() > 0 ? list.get(0) : null);
    }

    /**
     * 库位打印
     *
     * @param printReq 打印Req
     * @return R 对象
     */
    @Operation(summary = "库位打印", description = "库位打印")
    @GetMapping("/print")
    public R<String> print(@ParameterObject PrintReq printReq) {
        return R.ok(mdmWarehouseLocationService.print(printReq));
    }

    /**
     * 新增库位
     *
     * @param mdmWarehouseLocation 库位
     * @return R
     */
    @Operation(summary = "新增库位", description = "新增库位")
    @SysLog("新增库位")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmWarehouseLocation_add')")
    public R<Boolean> save(@RequestBody MdmWarehouseLocationEntity mdmWarehouseLocation) {
        return R.ok(mdmWarehouseLocationService.save(mdmWarehouseLocation));
    }

    /**
     * 修改库位
     *
     * @param mdmWarehouseLocation 库位
     * @return R
     */
    @Operation(summary = "修改库位", description = "修改库位")
    @SysLog("修改库位")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmWarehouseLocation_edit')")
    public R<Boolean> updateById(@RequestBody MdmWarehouseLocationEntity mdmWarehouseLocation) {
        FactoryContextHolder.setFactorySkip();
        return R.ok(mdmWarehouseLocationService.updateById(mdmWarehouseLocation));
    }

    /**
     * 通过id删除库位
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除库位", description = "通过id删除库位")
    @SysLog("通过id删除库位")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmWarehouseLocation_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        FactoryContextHolder.setFactorySkip();
        return R.ok(mdmWarehouseLocationService.removeTreeBatchByIds(CollUtil.toList(ids)));
    }

    /**
     * 导出excel 表格
     *
     * @param mdmWarehouseLocation 查询条件
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mdm_mdmWarehouseLocation_export')")
    public List<MdmWarehouseLocationExcelVO> exportExcel(MdmWarehouseLocationEntity mdmWarehouseLocation) {
        FactoryContextHolder.setFactorySkip();
        return mdmWarehouseLocationService.exportExcel(mdmWarehouseLocation);
    }

    /**
     * 导入excel 表
     *
     * @param excelVOList   库位列表
     * @param bindingResult 错误信息列表
     * @return ok fail
     */
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('mdm_mdmWarehouseLocation_export')")
    public R importExcel(@RequestExcel(headRowNumber = 2) List<MdmWarehouseLocationExcelVO> excelVOList, BindingResult bindingResult) {
        return mdmWarehouseLocationService.importExcel(excelVOList, bindingResult);
    }

}
