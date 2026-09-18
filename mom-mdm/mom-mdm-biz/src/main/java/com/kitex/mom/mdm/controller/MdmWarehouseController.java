package com.kitex.mom.mdm.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.excel.annotation.RequestExcel;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.enums.WarehouseTypeEnum;
import com.kitex.mom.mdm.service.MdmWarehouseService;
import com.kitex.mom.mdm.vo.MdmWarehouseModelTreeVO;
import com.kitex.mom.mdm.vo.excel.MdmWarehouseExcelVO;
import com.kitex.mom.mdm.vo.excel.MdmWarehouseModelExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 仓库
 *
 * @author kitex
 * @date 2024-10-28 13:48:36
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmWarehouse")
@Tag(description = "mdmWarehouse", name = "仓库管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmWarehouseController {

    private final MdmWarehouseService mdmWarehouseService;

    @Operation(summary = "获取仓库建模树形结构")
    @GetMapping("/tree")
    @PreAuthorize("@pms.hasPermission('mdm_mdmWarehouse_view')")
    public R<List<MdmWarehouseModelTreeVO>> getWarehouseModelTree() {
        List<MdmWarehouseModelTreeVO> tree = mdmWarehouseService.getWarehouseModelTree();
        return R.ok(tree);
    }

    /**
     * 分页查询
     *
     * @param page         分页对象
     * @param mdmWarehouse 仓库
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mdm_mdmWarehouse_view')")
    public R<Page<MdmWarehouseEntity>> getMdmWarehousePage(SearchPage page, @ParameterObject MdmWarehouseEntity mdmWarehouse) {
        QueryWrapper<MdmWarehouseEntity> wrapper = new QueryWrapper<>();
        if (ObjUtil.isNotEmpty(mdmWarehouse.getFactoryCode())) {
            // 跳过工厂验证
            FactoryContextHolder.setFactorySkip();
            List<String> factoryCodes = Arrays.stream(mdmWarehouse.getFactoryCode().split(",")).toList();
            wrapper.lambda()
                    .in(MdmWarehouseEntity::getFactoryCode, factoryCodes);
        }
        wrapper.lambda().like(StrUtil.isNotEmpty(mdmWarehouse.getWarehouseCode()), MdmWarehouseEntity::getWarehouseCode,
                        mdmWarehouse.getWarehouseCode())
                .like(StrUtil.isNotEmpty(mdmWarehouse.getWarehouseName()), MdmWarehouseEntity::getWarehouseName,
                        mdmWarehouse.getWarehouseName())
                .eq(ObjUtil.isNotEmpty(mdmWarehouse.getStatus()), MdmWarehouseEntity::getStatus, mdmWarehouse.getStatus())
                .eq(ObjUtil.isNotEmpty(mdmWarehouse.getWarehouseType()), MdmWarehouseEntity::getWarehouseType, mdmWarehouse.getWarehouseType())
                .orderByAsc(MdmWarehouseEntity::getSort);
        return R.ok(mdmWarehouseService.page(page.toMpPage(), wrapper));
    }

    /**
     * 查询各仓库状态的数量
     *
     * @return
     */
    @Operation(summary = "查询各仓库状态的数量", description = "查询各仓库状态的数量")
    @GetMapping("/getCount")
    public R<Map<Boolean, Long>> getCount(@ParameterObject MdmWarehouseEntity mdmWarehouse) {
        QueryWrapper<MdmWarehouseEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().select(MdmWarehouseEntity::getStatus)
                .like(StrUtil.isNotEmpty(mdmWarehouse.getWarehouseCode()), MdmWarehouseEntity::getWarehouseCode, mdmWarehouse.getWarehouseCode())
                .like(StrUtil.isNotEmpty(mdmWarehouse.getWarehouseName()), MdmWarehouseEntity::getWarehouseName, mdmWarehouse.getWarehouseName())
                .eq(ObjUtil.isNotEmpty(mdmWarehouse.getWarehouseType()), MdmWarehouseEntity::getWarehouseType, mdmWarehouse.getWarehouseType());
        return R.ok(mdmWarehouseService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(MdmWarehouseEntity::getStatus, Collectors.counting())));
    }

    /**
     * 获取仓库list
     */
    @Operation(summary = "获取仓库list", description = "获取仓库list")
    @GetMapping("/list")
    public R<List<MdmWarehouseEntity>> getMdmWarehouseList(@ParameterObject MdmWarehouseEntity mdmWarehouse) {
        QueryWrapper<MdmWarehouseEntity> queryWrapper = Wrappers.query();
        queryWrapper.lambda().eq(MdmWarehouseEntity::getStatus, true)
                .like(StrUtil.isNotEmpty(mdmWarehouse.getWarehouseCode()), MdmWarehouseEntity::getWarehouseCode, mdmWarehouse.getWarehouseCode())
                .like(StrUtil.isNotEmpty(mdmWarehouse.getWarehouseName()), MdmWarehouseEntity::getWarehouseName, mdmWarehouse.getWarehouseName())
                .orderByAsc(MdmWarehouseEntity::getSort);
        return R.ok(mdmWarehouseService.list(queryWrapper));
    }

    @Inner
    @GetMapping("/productEnabled")
    public R<List<MdmWarehouseEntity>> getEnabledProductWarehouses() {
        String factory = FactoryContextHolder.getFactoryCode();
        if (StrUtil.isBlank(factory) || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())) {
            throw new com.kitex.common.core.exception.CheckedException("成品仓查询必须处于精确工厂上下文");
        }
        return R.ok(mdmWarehouseService.list(new LambdaQueryWrapper<MdmWarehouseEntity>()
                .eq(MdmWarehouseEntity::getFactoryCode, factory)
                .eq(MdmWarehouseEntity::getWarehouseType, WarehouseTypeEnum.PRODUCT.getCode())
                .eq(MdmWarehouseEntity::getStatus, true)
                .orderByAsc(MdmWarehouseEntity::getSort)
                .orderByAsc(MdmWarehouseEntity::getId)));
    }

    /**
     * 根据编码获取仓库
     */
    @Operation(summary = "根据编码获取仓库", description = "根据编码获取仓库")
    @GetMapping("/getByCode")
    public R<MdmWarehouseEntity> getByCode(@RequestParam String warehouseCode) {
        LambdaQueryWrapper<MdmWarehouseEntity> queryWrapper = Wrappers.lambdaQuery();
        queryWrapper.eq(MdmWarehouseEntity::getWarehouseCode, warehouseCode);
        return R.ok(mdmWarehouseService.getOne(queryWrapper, false));
    }

    /**
     * 新增仓库
     *
     * @param mdmWarehouse 仓库
     * @return R
     */
    @Operation(summary = "新增仓库", description = "新增仓库")
    @SysLog("新增仓库")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmWarehouse_add')")
    public R<Boolean> save(@RequestBody MdmWarehouseEntity mdmWarehouse) {
        return R.ok(mdmWarehouseService.save(mdmWarehouse));
    }

    /**
     * 修改仓库
     *
     * @param mdmWarehouse 仓库
     * @return R
     */
    @Operation(summary = "修改仓库", description = "修改仓库")
    @SysLog("修改仓库")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmWarehouse_edit')")
    public R<Boolean> updateById(@RequestBody MdmWarehouseEntity mdmWarehouse) {
        FactoryContextHolder.setFactorySkip();
        return R.ok(mdmWarehouseService.updateById(mdmWarehouse));
    }


    /**
     * 修改仓库配置
     */
    @Operation(summary = "修改仓库配置", description = "修改仓库配置")
    @PutMapping("/updateConfig")
    public R<Boolean> updateConfig(@RequestBody MdmWarehouseEntity mdmWarehouse) {
        return R.ok(mdmWarehouseService.update(new UpdateWrapper<MdmWarehouseEntity>()
                .lambda().set(MdmWarehouseEntity::getIsBatch, mdmWarehouse.getIsBatch())
                .set(MdmWarehouseEntity::getIsContainer, mdmWarehouse.getIsContainer())
                .eq(MdmWarehouseEntity::getWarehouseCode, mdmWarehouse.getWarehouseCode())));
    }

    /**
     * 通过id删除仓库
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除仓库", description = "通过id删除仓库")
    @SysLog("通过id删除仓库")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmWarehouse_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        FactoryContextHolder.setFactorySkip();
        return R.ok(mdmWarehouseService.removeBatchByIds(CollUtil.toList(ids)));
    }

    /**
     * 根据条件查询仓库信息
     *
     * @param query
     * @return
     */
    @Operation(summary = "通过条件查询", description = "通过条件查询对象")
    @GetMapping("/details")
    @PreAuthorize("@pms.hasPermission('mdm_mdmWarehouse_view')")
    public R<MdmWarehouseEntity> getDetails(@ParameterObject MdmWarehouseEntity query) {
        if (ObjUtil.isNotEmpty(query.getSkipFactory())) {
            FactoryContextHolder.setFactorySkip();
        }
        return R.ok(mdmWarehouseService.getOne(Wrappers.query(query), false));
    }

    /**
     * 导入excel 表格
     *
     * @param excelVOList   仓库列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('mdm_mdmWarehouse_export')")
    public R importWarehouse(@RequestExcel(headRowNumber = 2) List<MdmWarehouseModelExcelVO> excelVOList, BindingResult bindingResult) {
        FactoryContextHolder.setFactorySkip();
        return mdmWarehouseService.importWarehouse(excelVOList, bindingResult);
    }

    /**
     * 导出excel 表格(仓储、库区)
     *
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/exportAll")
    @PreAuthorize("@pms.hasPermission('mdm_mdmWarehouse_export')")
    public List<MdmWarehouseModelExcelVO> exportAll() {
        FactoryContextHolder.setFactorySkip();
        return mdmWarehouseService.exportAll();
    }

    /**
     * 导出excel 表格
     *
     * @param mdmWarehouse 查询条件
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mdm_mdmWarehouse_export')")
    public List<MdmWarehouseExcelVO> export(MdmWarehouseEntity mdmWarehouse) {
        FactoryContextHolder.setFactorySkip();
        return mdmWarehouseService.export(mdmWarehouse);
    }

}
