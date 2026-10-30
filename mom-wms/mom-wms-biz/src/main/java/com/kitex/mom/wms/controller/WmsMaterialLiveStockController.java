package com.kitex.mom.wms.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.excel.annotation.RequestExcel;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.mom.plan.dto.TemporaryPlanQueryDTO;
import com.kitex.mom.wms.dto.WmsLiveStockByProductCodeDto;
import com.kitex.mom.wms.dto.WmsLiveStockDynamicGroupDTO;
import com.kitex.mom.wms.dto.WmsLiveStockQueryDTO;
import com.kitex.mom.wms.dto.WmsLiveStockQuerySumDTO;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.enums.menu.LiveStockMenuTypeEnum;
import com.kitex.mom.wms.service.WmsLiveStockService;
import com.kitex.mom.wms.service.impl.WmsLiveStockReadAccess;
import com.kitex.mom.wms.service.impl.WmsOperationStockSummaryAccess;
import com.kitex.mom.wms.vo.WmsLiveStockByMaterialExcelVO;
import com.kitex.mom.wms.vo.WmsLiveStockBySupplierExcelVO;
import com.kitex.mom.wms.vo.WmsLiveStockDetailExcelVO;
import com.kitex.mom.wms.vo.WmsLiveStockImportExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 实时库存
 *
 * @author kitex
 * @date 2024-12-18 14:31:45
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/material/wmsLiveStock")
@Tag(description = "wmsLiveStock", name = "实时库存")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsMaterialLiveStockController {

    private final WmsLiveStockService wmsLiveStockService;
    private final WmsLiveStockReadAccess readAccess;
    private final WmsOperationStockSummaryAccess operationSummaryAccess;

    /**
     * 获取库存条数
     */
    @Operation(summary = "获取库存条数", description = "获取库存条数")
    @GetMapping("/getLiveStockDetailNum")
    public R getLiveStockDetailNum(@RequestParam String warehouseCode) {
        readAccess.requireView(LiveStockMenuTypeEnum.STOCK_DETAIL, warehouseCode);
        return R.ok(wmsLiveStockService.count(new LambdaQueryWrapper<WmsLiveStockEntity>()
                .eq(WmsLiveStockEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(WmsLiveStockEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                .eq(WmsLiveStockEntity::getWarehouseCode, warehouseCode)
                .ne(WmsLiveStockEntity::getMatNum, 0)
        ));
    }

    /**
     * 分页查询-库存明细
     *
     * @param page         分页对象
     * @param wmsLiveStock 实时库存
     * @return
     */
    @Operation(summary = "分页查询-库存明细", description = "分页查询-库存明细")
    @GetMapping("/page")
    public R getWmsLiveStockPage(@ParameterObject SearchPage page, @ParameterObject WmsLiveStockQueryDTO wmsLiveStock) {
        readAccess.requireView(LiveStockMenuTypeEnum.STOCK_DETAIL, wmsLiveStock.getWarehouseCode());
        return R.ok(wmsLiveStockService.pageDetail(page.toMpPage(), wmsLiveStock));
    }

    /**
     * 分页查询-物料级
     *
     * @param page         分页对象
     * @param wmsLiveStock 实时库存
     * @return
     */
    @Operation(summary = "分页查询-物料级", description = "分页查询-物料级")
    @GetMapping("/pageByMat")
    public R getWmsLiveStockPageByMat(@ParameterObject SearchPage page, @ParameterObject WmsLiveStockQueryDTO wmsLiveStock) {
        readAccess.requireView(LiveStockMenuTypeEnum.BY_MATERIAL, wmsLiveStock.getWarehouseCode());
        QueryWrapper<WmsLiveStockEntity> wrapper = getQueryWrapperByMat(wmsLiveStock);
        return R.ok(wmsLiveStockService.page(page.toMpPage(), wrapper));
    }

    public QueryWrapper<WmsLiveStockEntity> getQueryWrapperByMat(WmsLiveStockQueryDTO wmsLiveStock) {
        QueryWrapper<WmsLiveStockEntity> wrapper = new QueryWrapper<>();
        wrapper.select("mat_code", "mat_name", "mat_spec", "mat_unit", "sum(mat_num) as mat_num");
        wrapper.lambda().eq(WmsLiveStockEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(WmsLiveStockEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                .like(StrUtil.isNotBlank(wmsLiveStock.getMatCode()), WmsLiveStockEntity::getMatCode, wmsLiveStock.getMatCode())
                .in(CollUtil.isNotEmpty(wmsLiveStock.getSiteCode()), WmsLiveStockEntity::getSiteCode, wmsLiveStock.getSiteCode())
                .like(StrUtil.isNotBlank(wmsLiveStock.getMatName()), WmsLiveStockEntity::getMatName, wmsLiveStock.getMatName())
                .like(StrUtil.isNotBlank(wmsLiveStock.getMatBatch()), WmsLiveStockEntity::getMatBatch, wmsLiveStock.getMatBatch())
                .eq(StrUtil.isNotBlank(wmsLiveStock.getWarehouseCode()), WmsLiveStockEntity::getWarehouseCode, wmsLiveStock.getWarehouseCode())
                .groupBy(WmsLiveStockEntity::getMatCode, WmsLiveStockEntity::getMatName, WmsLiveStockEntity::getMatSpec, WmsLiveStockEntity::getMatUnit)
                .orderByAsc(WmsLiveStockEntity::getMatCode);
        return wrapper;
    }

    /**
     * 分页查询-供应商级
     *
     * @param page         分页对象
     * @param wmsLiveStock 实时库存
     * @return
     */
    @Operation(summary = "分页查询-供应商级", description = "分页查询-供应商级")
    @GetMapping("/pageBySupplier")
    public R pageBySupplier(@ParameterObject SearchPage page, @ParameterObject WmsLiveStockQueryDTO wmsLiveStock) {
        readAccess.requireView(LiveStockMenuTypeEnum.BY_SUPPLIER, wmsLiveStock.getWarehouseCode());
        QueryWrapper<WmsLiveStockEntity> wrapper = getQueryWrapperBySupplier(wmsLiveStock);
        return R.ok(wmsLiveStockService.page(page.toMpPage(), wrapper));
    }

    public QueryWrapper<WmsLiveStockEntity> getQueryWrapperBySupplier(WmsLiveStockQueryDTO wmsLiveStock) {
        QueryWrapper<WmsLiveStockEntity> wrapper = new QueryWrapper<>();
        wrapper.select("supplier_code", "supplier_name", "mat_code", "mat_name", "mat_unit", "sum(mat_num) as mat_num");
        wrapper.lambda().eq(WmsLiveStockEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(WmsLiveStockEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                .like(StrUtil.isNotBlank(wmsLiveStock.getMatCode()), WmsLiveStockEntity::getMatCode, wmsLiveStock.getMatCode())
                .in(CollUtil.isNotEmpty(wmsLiveStock.getSiteCode()), WmsLiveStockEntity::getSiteCode, wmsLiveStock.getSiteCode())
                .like(StrUtil.isNotBlank(wmsLiveStock.getSupplierCode()), WmsLiveStockEntity::getSupplierCode, wmsLiveStock.getSupplierCode())
                .like(StrUtil.isNotBlank(wmsLiveStock.getSupplierName()), WmsLiveStockEntity::getSupplierName, wmsLiveStock.getSupplierName())
                .like(StrUtil.isNotBlank(wmsLiveStock.getMatName()), WmsLiveStockEntity::getMatName, wmsLiveStock.getMatName())
                .eq(StrUtil.isNotBlank(wmsLiveStock.getWarehouseCode()), WmsLiveStockEntity::getWarehouseCode, wmsLiveStock.getWarehouseCode())
                .groupBy(WmsLiveStockEntity::getSupplierCode, WmsLiveStockEntity::getSupplierName, WmsLiveStockEntity::getMatCode, WmsLiveStockEntity::getMatName, WmsLiveStockEntity::getMatUnit)
                .orderByAsc(WmsLiveStockEntity::getSupplierCode, WmsLiveStockEntity::getMatCode);
        return wrapper;
    }

    /**
     * 分页查询-出库单新增
     *
     * @param page         分页对象
     * @param wmsLiveStock 实时库存
     * @return
     */
    @Operation(summary = "分页查询-出库单新增", description = "分页查询-出库单新增")
    @GetMapping("/pageWhenOut")
    public R pageWhenOut(@ParameterObject SearchPage page, @ParameterObject WmsLiveStockQueryDTO wmsLiveStock) {
        return R.ok(wmsLiveStockService.pageWhenOut(page.toMpPage(), wmsLiveStock));
    }

    /**
     * 查询汇总一条数据
     *
     * @param params 参数
     * @return 一条汇总信息
     */
    @Operation(summary = "查询汇总一条数据", description = "查询汇总一条数据")
    @GetMapping("/getSumByParams")
    public R getSumByParams(@Valid @ParameterObject WmsLiveStockQuerySumDTO params,
            @RequestParam("operation") String operation) {
        operationSummaryAccess.require(operation, params.getWarehouseCode());
        return R.ok(wmsLiveStockService.getSumByParams(params));
    }


    /**
     * 通过id查询实时库存
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    public R getById(@PathVariable("id") String id) {
        WmsLiveStockEntity stock = wmsLiveStockService.getById(id);
        readAccess.requireViewRow(stock);
        return R.ok(stock);
    }

    /**
     * 导出 明细 表格
     *
     * @param wmsLiveStock 查询条件
     * @param ids          导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    public List<WmsLiveStockDetailExcelVO> export(WmsLiveStockQueryDTO wmsLiveStock, String[] ids) {
        readAccess.requireExport(LiveStockMenuTypeEnum.STOCK_DETAIL, wmsLiveStock.getWarehouseCode());
        return wmsLiveStockService.listDetail(wmsLiveStock, ids);
    }

    /**
     * 导出 物料级 表格
     *
     * @param wmsLiveStock 查询条件
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/exportByMat")
    public List<WmsLiveStockByMaterialExcelVO> exportByMat(WmsLiveStockQueryDTO wmsLiveStock) {
        readAccess.requireExport(LiveStockMenuTypeEnum.BY_MATERIAL, wmsLiveStock.getWarehouseCode());
        return wmsLiveStockService.listByMat(getQueryWrapperByMat(wmsLiveStock));
    }

    /**
     * 导出 供应商级 表格
     *
     * @param wmsLiveStock 查询条件
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/exportBySupplier")
    public List<WmsLiveStockBySupplierExcelVO> exportBySupplier(WmsLiveStockQueryDTO wmsLiveStock) {
        readAccess.requireExport(LiveStockMenuTypeEnum.BY_SUPPLIER, wmsLiveStock.getWarehouseCode());
        return wmsLiveStockService.listBySupplier(getQueryWrapperBySupplier(wmsLiveStock));
    }

    /**
     * 期初导入
     *
     * @param excelVOS      对象实体列表
     * @param bindingResult 错误信息列表
     * @return ok fail
     */
    @PostMapping("/import")
    public R importExcel(@RequestExcel List<WmsLiveStockImportExcelVO> excelVOS, BindingResult bindingResult,
                         String warehouseCode) {
        if (excelVOS == null || excelVOS.isEmpty()) {
            throw new CheckedException("导入文件没有库存数据");
        }
        if (StrUtil.isNotBlank(warehouseCode)) {
            readAccess.requireExport(LiveStockMenuTypeEnum.STOCK_DETAIL, warehouseCode);
            for (WmsLiveStockImportExcelVO row : excelVOS) {
                if (row == null || (StrUtil.isNotBlank(row.getWarehouseCode())
                        && !warehouseCode.equals(row.getWarehouseCode()))) {
                    throw new CheckedException("导入行仓库与目标仓库不一致");
                }
            }
        } else {
            for (WmsLiveStockImportExcelVO row : excelVOS) {
                if (row == null || StrUtil.isBlank(row.getWarehouseCode())) {
                    throw new CheckedException("导入行缺少仓库编码");
                }
                readAccess.requireExport(LiveStockMenuTypeEnum.STOCK_DETAIL, row.getWarehouseCode());
            }
        }
        return wmsLiveStockService.importExcel(excelVOS, bindingResult, warehouseCode,
                FactoryContextHolder.getFactoryCode());
    }

    /**
     * 根据产品编码集合查询实时库存
     */
    @GetMapping("/getLiveStockByProductCodeList")
    public R<List<WmsLiveStockEntity>> getLiveStockByProductCodeList(WmsLiveStockByProductCodeDto w) {
        return R.ok(wmsLiveStockService.getLiveStockByProductCodeList(w.getProductCodeSet(), w.getWarehouseCode()));
    }

    /**
     * 根据产品编码集合查询实时库存
     */
    @GetMapping("/getLiveStockList")
    public R<List<WmsLiveStockEntity>> getLiveStock(@ParameterObject WmsLiveStockQueryDTO wmsLiveStock) {
        QueryWrapper<WmsLiveStockEntity> wrapper = getQueryWrapperByMat(wmsLiveStock);
        return R.ok(wmsLiveStockService.list(wrapper));
    }

    /**
     * 查询各仓库对应的物料数量
     *
     * @param temporaryPlanQueryDTO 查询DTO
     * @return
     */
    @Operation(summary = "查询各仓库对应的物料数量", description = "查询各仓库对应的物料数量")
    @GetMapping("/warehouse/liveStock")
    public R<List<Map<String, Object>>> getWarehouseLiveStock(@ParameterObject TemporaryPlanQueryDTO temporaryPlanQueryDTO) {
        return R.ok(wmsLiveStockService.getWarehouseLiveStock(temporaryPlanQueryDTO));
    }

    /**
     * 动态分组查询库存
     *
     * @param queryDTO 查询条件
     * @return 分组后的库存汇总数据，带分页
     */
    @Operation(summary = "动态分组查询库存", description = "根据指定字段动态分组查询库存，支持分页")
    @GetMapping("/liveStock/queryByDynamicGroup")
    public R queryByDynamicGroup(@ParameterObject SearchPage page, @ParameterObject WmsLiveStockDynamicGroupDTO queryDTO) {
        readAccess.requireView(LiveStockMenuTypeEnum.STOCK_DETAIL, queryDTO.getWarehouseCode());
        return R.ok(wmsLiveStockService.queryByDynamicGroup(page.toMpPage(), queryDTO));
    }
}
