package com.kitex.mom.wms.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.RequestExcel;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.CreateContainerBarcodeDTO;
import com.kitex.mom.wms.entity.WmsContainerBarcodeEntity;
import com.kitex.mom.wms.service.WmsContainerBarcodeService;
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
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 容器码
 *
 * @author kitex
 * @date 2025-01-02 14:57:19
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsContainerBarcode")
@Tag(description = "wmsContainerBarcode", name = "容器码管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsContainerBarcodeController {

    private final WmsContainerBarcodeService wmsContainerBarcodeService;

    /**
     * 分页查询
     *
     * @param page                分页对象
     * @param wmsContainerBarcode 容器码
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('wms_wmsContainerBarcode_view')")
    public R getWmsContainerBarcodePage(@ParameterObject SearchPage page, @ParameterObject WmsContainerBarcodeEntity wmsContainerBarcode) {
        LambdaQueryWrapper<WmsContainerBarcodeEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(wmsContainerBarcode.getCode()), WmsContainerBarcodeEntity::getCode, wmsContainerBarcode.getCode());
        wrapper.eq(Objects.nonNull(wmsContainerBarcode.getIsUsed()), WmsContainerBarcodeEntity::getIsUsed, wmsContainerBarcode.getIsUsed());
        wrapper.eq(StrUtil.isNotBlank(wmsContainerBarcode.getTypeCode()), WmsContainerBarcodeEntity::getTypeCode, wmsContainerBarcode.getTypeCode());
        wrapper.like(StrUtil.isNotBlank(wmsContainerBarcode.getWarehouseCode()), WmsContainerBarcodeEntity::getWarehouseCode, wmsContainerBarcode.getWarehouseCode());
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return R.ok(wmsContainerBarcodeService.page(page.toMpPage(), wrapper));
    }

    /**
     * 分页查询统计
     */
    @Operation(summary = "分页查询统计", description = "分页查询统计")
    @GetMapping("/getCount")
    public R<Map<Integer, Long>> getCount(@ParameterObject WmsContainerBarcodeEntity wmsContainerBarcode) {
        LambdaQueryWrapper<WmsContainerBarcodeEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(wmsContainerBarcode.getCode()), WmsContainerBarcodeEntity::getCode, wmsContainerBarcode.getCode());
        wrapper.eq(StrUtil.isNotBlank(wmsContainerBarcode.getTypeCode()), WmsContainerBarcodeEntity::getTypeCode, wmsContainerBarcode.getTypeCode());
        wrapper.like(StrUtil.isNotBlank(wmsContainerBarcode.getWarehouseCode()), WmsContainerBarcodeEntity::getWarehouseCode, wmsContainerBarcode.getWarehouseCode());
        return R.ok(wmsContainerBarcodeService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(WmsContainerBarcodeEntity::getIsUsed, Collectors.counting())));
    }


    /**
     * 通过条件查询容器码
     *
     * @param wmsContainerBarcode 查询条件
     * @return R  对象列表
     */
    @Operation(summary = "通过条件查询", description = "通过条件查询对象")
    @GetMapping("/details")
    @PreAuthorize("@pms.hasPermission('wms_wmsContainerBarcode_view')")
    public R getDetails(@ParameterObject WmsContainerBarcodeEntity wmsContainerBarcode) {
        return R.ok(wmsContainerBarcodeService.list(Wrappers.query(wmsContainerBarcode)));
    }


    /**
     * 通过条件查询容器码
     *
     * @param code
     * @return R  对象列表
     */
    @Operation(summary = "通过条件查询", description = "通过条件查询对象")
    @GetMapping("/queryBarcode")
    public R queryBarcode(String code) {
        return R.ok(wmsContainerBarcodeService.getOne(new LambdaQueryWrapper<WmsContainerBarcodeEntity>()
                .eq(WmsContainerBarcodeEntity::getCode, code)));
    }

    /**
     * 新增容器码
     *
     * @param wmsContainerBarcode 容器码
     * @return R
     */
    @Operation(summary = "新增容器码", description = "新增容器码")
    @SysLog("新增容器码")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('wms_wmsContainerBarcode_add')")
    public R save(@RequestBody WmsContainerBarcodeEntity wmsContainerBarcode) {
        return R.ok(wmsContainerBarcodeService.save(wmsContainerBarcode));
    }

    /**
     * 批量生成容器码
     *
     * @param wmsContainerBarcode 参数
     * @return R
     */
    @Operation(summary = "批量生成容器码", description = "批量生成容器码")
    @SysLog("批量生成容器码")
    @PostMapping("/multiCreate")
    @PreAuthorize("@pms.hasPermission('wms_wmsContainerBarcode_add')")
    public R multiCreate(@RequestBody CreateContainerBarcodeDTO wmsContainerBarcode) {
        return R.ok(wmsContainerBarcodeService.multiCreate(wmsContainerBarcode));
    }

    /**
     * 修改容器码
     *
     * @param wmsContainerBarcode 容器码
     * @return R
     */
    @Operation(summary = "修改容器码", description = "修改容器码")
    @SysLog("修改容器码")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('wms_wmsContainerBarcode_edit')")
    public R updateById(@RequestBody WmsContainerBarcodeEntity wmsContainerBarcode) {
        return R.ok(wmsContainerBarcodeService.updateById(wmsContainerBarcode));
    }

    /**
     * 通过id删除容器码
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除容器码", description = "通过id删除容器码")
    @SysLog("通过id删除容器码")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('wms_wmsContainerBarcode_del')")
    public R removeById(@RequestBody Long[] ids) {
        return R.ok(wmsContainerBarcodeService.removeBatchByIds(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param wmsContainerBarcode 查询条件
     * @param ids                 导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('wms_wmsContainerBarcode_export')")
    public List<WmsContainerBarcodeEntity> exportExcel(WmsContainerBarcodeEntity wmsContainerBarcode, Long[] ids) {
        return wmsContainerBarcodeService.list(Wrappers.lambdaQuery(wmsContainerBarcode).in(ArrayUtil.isNotEmpty(ids), WmsContainerBarcodeEntity::getId, ids));
    }

    /**
     * 导入excel 表
     *
     * @param wmsContainerBarcodeList 对象实体列表
     * @param bindingResult           错误信息列表
     * @return ok fail
     */
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('wms_wmsContainerBarcode_export')")
    public R importExcel(@RequestExcel List<WmsContainerBarcodeEntity> wmsContainerBarcodeList, BindingResult bindingResult) {
        return R.ok(wmsContainerBarcodeService.saveBatch(wmsContainerBarcodeList));
    }
}