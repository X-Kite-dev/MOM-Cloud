package com.kitex.mom.plan.controller;


import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.RequestExcel;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.oms.dto.HistoryDeliverOrderDto;
import com.kitex.mom.oms.dto.OmsOrderBasePageDto;
import com.kitex.mom.oms.entity.OmsOrderBaseEntity;
import com.kitex.mom.oms.entity.OmsOrderDetailsEntity;
import com.kitex.mom.oms.vo.OmsOrderBaseExportVo;
import com.kitex.mom.oms.vo.OmsOrderBaseImportVo;
import com.kitex.mom.plan.service.OmsOrderBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 订单输入
 *
 * @author kitex
 * @date 2025-01-07 14:20:47
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/omsOrderBase")
@Tag(description = "omsOrderBase", name = "订单输入管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class OmsOrderBaseController {

    private final OmsOrderBaseService omsOrderBaseService;

    /**
     * 分页查询
     *
     * @param page         分页对象
     * @param omsOrderBase 订单输入
     */

    @Operation(summary = "订单输入分页查询", description = "订单输入分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBase_view')")
    public R<Page<OmsOrderBasePageDto>> getOmsOrderBasePage(@ParameterObject Page<OmsOrderBasePageDto> page, @ParameterObject OmsOrderBasePageDto omsOrderBase) {
        return R.ok(omsOrderBaseService.getOmsOrderBasePage(page, omsOrderBase));
    }

    /**
     * 查询各订单输入状态的数量
     *
     * @return
     */

    @Operation(summary = "查询各订单输入状态的数量", description = "查询各订单输入状态的数量")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject OmsOrderBasePageDto omsOrderBase) {
        return R.ok(omsOrderBaseService.getCount(omsOrderBase));
    }

    /**
     * 通过id查询订单输入
     */
    @Operation(summary = "通过id查询订单输入", description = "通过id查询订单输入")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBase_view')")
    public R<OmsOrderBaseEntity> getOrderById(@PathVariable("id") Long id) {
        return R.ok(omsOrderBaseService.getOrderById(id));
    }

    /**
     * 根据产品编码查询历史未交信息
     */
    @Operation(summary = "根据产品编码查询历史未交信息", description = "根据产品编码查询历史未交信息")
    @GetMapping("/getHistoryDeliverOrderDtoList")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBase_view')")
    public R<List<HistoryDeliverOrderDto>> getHistoryDeliverOrderDtoList(@RequestParam("orderCode") String orderCode,
                                                                         @RequestParam("productCode") String productCode) {
        return R.ok(omsOrderBaseService.getHistoryDeliverOrderDtoList(orderCode, productCode));
    }


    /**
     * 新增订单输入
     *
     * @param omsOrderBase 订单输入
     */
    @Operation(summary = "新增订单输入", description = "新增订单输入")
    @SysLog("新增订单输入")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBase_add')")
    public R<Boolean> saveOrder(@RequestBody OmsOrderBaseEntity omsOrderBase) {
        return R.ok(omsOrderBaseService.saveOrder(omsOrderBase));
    }

    /**
     * 修改订单输入
     *
     * @param omsOrderBase 订单输入
     */
    @Operation(summary = "修改订单输入", description = "修改订单输入")
    @SysLog("修改订单输入")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBase_edit')")
    public R<Boolean> updateOrderById(@RequestBody OmsOrderBaseEntity omsOrderBase) {
        return R.ok(omsOrderBaseService.updateOrderById(omsOrderBase));
    }

    /**
     * 通过id删除订单输入
     *
     * @param ids id列表
     */
    @Operation(summary = "通过id删除订单输入", description = "通过id删除订单输入")
    @SysLog("通过id删除订单输入")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBase_del')")
    public R<Boolean> removeOrderByIds(@RequestBody Long[] ids) {
        return R.ok(omsOrderBaseService.removeOrderByIds(CollUtil.toList(ids)));
    }

    /**
     * 导入excel 表格
     *
     * @param omsOrderBaseImportVoList 订单导入列表
     * @param bindingResult            错误信息列表
     */
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBase_add')")
    public R importOrder(@RequestExcel(headRowNumber = 2) List<OmsOrderBaseImportVo> omsOrderBaseImportVoList, BindingResult bindingResult,
                         @RequestParam("factoryCode") String factoryCode) {
        return omsOrderBaseService.importOrder(omsOrderBaseImportVoList, bindingResult, factoryCode);
    }

    /**
     * 导出excel 表格
     *
     * @param omsOrderBase 查询条件
     * @param ids          导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBase_export')")
    public List<OmsOrderBaseExportVo> export(OmsOrderBaseEntity omsOrderBase, Long[] ids) {
        return omsOrderBaseService.export(omsOrderBase, ids);
    }


    /**
     * 订单输入批量提交评审
     */
    @Operation(summary = "订单输入批量提交评审", description = "订单输入批量提交评审")
    @SysLog("订单输入批量提交评审")
    @PostMapping("/submit")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBase_submit')")
    public R<Boolean> submitReview(@RequestBody Long[] ids) {
        return R.ok(omsOrderBaseService.submitReview(CollUtil.toList(ids)));
    }


    /**
     * 订单输入批量直接通过
     */
    @Operation(summary = "订单输入批量直接通过", description = "订单输入批量直接通过")
    @SysLog("订单输入批量直接通过")
    @PostMapping("/pass")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBase_pass')")
    public R<Boolean> pass(@RequestBody Long[] ids) {
        return R.ok(omsOrderBaseService.pass(CollUtil.toList(ids)));
    }


    /**
     * 校验订单信息
     */
    @Operation(summary = "校验订单信息", description = "校验订单信息")
    @GetMapping("/checkOrderBaseMsg")
    public R<Boolean> checkOrderBaseMsg(@RequestParam("orderCode") String orderCode,
                                        @RequestParam("productCode") String productCode,
                                        @RequestParam("customerCode") String customerCode) {
        return R.ok(omsOrderBaseService.checkOrderBaseMsg(orderCode, productCode, customerCode));
    }

    /**
     * 查询订单明细
     */
    @Operation(summary = "查询订单明细", description = "查询订单明细")
    @GetMapping("/getOrderDetails")
    public R<OmsOrderDetailsEntity> getOrderDetails(@RequestParam("orderCode") String orderCode,
                                                    @RequestParam("productCode") String productCode,
                                                    @RequestParam("planDeliverTime") LocalDate planDeliverTime) {
        return R.ok(omsOrderBaseService.getOrderDetails(orderCode, productCode, planDeliverTime));
    }

}
