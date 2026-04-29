package com.kitex.mom.wms.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.WmsAllocationBillAddDTO;
import com.kitex.mom.wms.dto.WmsAllocationBillQueryDTO;
import com.kitex.mom.wms.dto.app.WmsAllocationBillAppDTO;
import com.kitex.mom.wms.entity.WmsAllocationBillEntity;
import com.kitex.mom.wms.service.WmsAllocationBillService;
import com.kitex.mom.wms.vo.WmsAllocationBillExcelVO;
import com.kitex.mom.wms.vo.WmsAllocationBillVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 调拨单
 *
 * @author kitex
 * @date 2024-12-18 14:09:03
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsAllocationBill")
@Tag(description = "wmsAllocationBill", name = "调拨单管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsAllocationBillController {

    private final WmsAllocationBillService wmsAllocationBillService;

    /**
     * 分页查询
     *
     * @param page              分页对象
     * @param wmsAllocationBill 调拨单
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
//    @PreAuthorize("@pms.hasPermission('wms_wmsAllocationBill_view')")
    public R<Page<WmsAllocationBillEntity>> getWmsAllocationBillPage(@ParameterObject SearchPage page, @ParameterObject WmsAllocationBillQueryDTO wmsAllocationBill) {
        LambdaQueryWrapper<WmsAllocationBillEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(wmsAllocationBill.getBillNo()), WmsAllocationBillEntity::getBillNo, wmsAllocationBill.getBillNo());
        wrapper.eq(StrUtil.isNotBlank(wmsAllocationBill.getOldWarehouseCode()), WmsAllocationBillEntity::getOldWarehouseCode, wmsAllocationBill.getOldWarehouseCode());
        wrapper.like(StrUtil.isNotBlank(wmsAllocationBill.getOldWarehouseName()), WmsAllocationBillEntity::getOldWarehouseName, wmsAllocationBill.getOldWarehouseName());
        wrapper.eq(StrUtil.isNotBlank(wmsAllocationBill.getNewWarehouseCode()), WmsAllocationBillEntity::getNewWarehouseCode, wmsAllocationBill.getNewWarehouseCode());
        wrapper.like(StrUtil.isNotBlank(wmsAllocationBill.getNewWarehouseName()), WmsAllocationBillEntity::getNewWarehouseName, wmsAllocationBill.getNewWarehouseName());
        wrapper.eq(StrUtil.isNotBlank(wmsAllocationBill.getAllocationState()), WmsAllocationBillEntity::getAllocationState, wmsAllocationBill.getAllocationState());
        wrapper.orderByDesc(WmsAllocationBillEntity::getCreateTime).orderByAsc(WmsAllocationBillEntity::getAllocationState);
        return R.ok(wmsAllocationBillService.page(page.toMpPage(), wrapper));
    }

    /**
     * 分页查询
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject WmsAllocationBillQueryDTO wmsAllocationBill) {
        LambdaQueryWrapper<WmsAllocationBillEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(wmsAllocationBill.getBillNo()), WmsAllocationBillEntity::getBillNo, wmsAllocationBill.getBillNo());
        wrapper.eq(StrUtil.isNotBlank(wmsAllocationBill.getOldWarehouseCode()), WmsAllocationBillEntity::getOldWarehouseCode, wmsAllocationBill.getOldWarehouseCode());
        wrapper.like(StrUtil.isNotBlank(wmsAllocationBill.getOldWarehouseName()), WmsAllocationBillEntity::getOldWarehouseName, wmsAllocationBill.getOldWarehouseName());
        wrapper.eq(StrUtil.isNotBlank(wmsAllocationBill.getNewWarehouseCode()), WmsAllocationBillEntity::getNewWarehouseCode, wmsAllocationBill.getNewWarehouseCode());
        wrapper.like(StrUtil.isNotBlank(wmsAllocationBill.getNewWarehouseName()), WmsAllocationBillEntity::getNewWarehouseName, wmsAllocationBill.getNewWarehouseName());
        return R.ok(wmsAllocationBillService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(WmsAllocationBillEntity::getAllocationState, Collectors.counting())));
    }


    /**
     * 通过id查询调拨单
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
//    @PreAuthorize("@pms.hasPermission('wms_wmsAllocationBill_view')")
    public R<WmsAllocationBillVO> getById(@PathVariable("id") Long id) {
        return R.ok(wmsAllocationBillService.getDetails(id));
    }

    /**
     * 新增调拨单
     *
     * @param wmsAllocationBillAdd 调拨单接收DTO
     * @return R
     */
    @Operation(summary = "新增调拨单", description = "新增调拨单")
    @SysLog("新增调拨单")
    @PostMapping
//    @PreAuthorize("@pms.hasPermission('wms_wmsAllocationBill_add')")
    public R<Boolean> save(@RequestBody WmsAllocationBillAddDTO wmsAllocationBillAdd) {
        return R.ok(wmsAllocationBillService.saveAllocationBill(wmsAllocationBillAdd));
    }

    /**
     * 调拨接收
     *
     * @param wmsAllocationBillAdd 调拨单接收DTO
     * @return R
     */
    @Operation(summary = "调拨接收", description = "调拨接收")
    @SysLog("调拨接收")
    @PutMapping
//    @PreAuthorize("@pms.hasPermission('wms_wmsAllocationBill_edit')")
    public R<Boolean> updateById(@RequestBody WmsAllocationBillAddDTO wmsAllocationBillAdd) {
        return R.ok(wmsAllocationBillService.updateAllocationBill(wmsAllocationBillAdd));
    }

    /**
     * app-新增调拨单
     *
     * @param wmsAllocationBillAdd 调拨单接收DTO
     * @return R
     */
    @Operation(summary = "app-新增调拨单", description = "app-新增调拨单")
    @SysLog("app-新增调拨单")
    @PostMapping("/app")
    public R<Boolean> appSave(@RequestBody @Valid WmsAllocationBillAppDTO wmsAllocationBillAdd) {
        return R.ok(wmsAllocationBillService.appSaveAllocationBill(wmsAllocationBillAdd));
    }

    /**
     * 通过id删除调拨单
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除调拨单", description = "通过id删除调拨单")
    @SysLog("通过id删除调拨单")
    @DeleteMapping
//    @PreAuthorize("@pms.hasPermission('wms_wmsAllocationBill_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(wmsAllocationBillService.removeBatchByIds(CollUtil.toList(ids)));
    }

    /**
     * 导出excel 表格
     *
     * @param wmsAllocationBill 查询条件
     * @param ids               导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
//    @PreAuthorize("@pms.hasPermission('wms_wmsAllocationBill_export')" )
    public List<WmsAllocationBillExcelVO> export(WmsAllocationBillEntity wmsAllocationBill, Long[] ids) {
        return wmsAllocationBillService.export(wmsAllocationBill, ids);
    }
}