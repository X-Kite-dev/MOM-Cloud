package com.kitex.mom.pms.controller;


import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.pms.entity.PmsRefundEntity;
import com.kitex.mom.pms.service.PmsRefundService;
import com.kitex.mom.pms.vo.PmsRefundVO;
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
 * 退款表
 *
 * @author kitex
 * @date 2025-04-08 17:24:20
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/pmsRefund")
@Tag(description = "pmsRefund", name = "退款表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PmsRefundController {

    private final PmsRefundService pmsRefundService;

    /**
     * 分页查询
     *
     * @param page      分页对象
     * @param pmsRefund 退款表
     * @return
     */

    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('pms_pmsAdvancePayment_view')")
    public R getPmsRefundPage(@ParameterObject SearchPage page, @ParameterObject PmsRefundEntity pmsRefund) {
        QueryWrapper<PmsRefundEntity> wrapper = Wrappers.query();
        wrapper.lambda()
                .like(StrUtil.isNotBlank(pmsRefund.getPaymentNo()), PmsRefundEntity::getPaymentNo, pmsRefund.getPaymentNo())
                .like(StrUtil.isNotBlank(pmsRefund.getRefundNo()), PmsRefundEntity::getRefundNo, pmsRefund.getRefundNo())
                .like(StrUtil.isNotBlank(pmsRefund.getSupplierCode()), PmsRefundEntity::getSupplierCode, pmsRefund.getSupplierCode())
                .like(StrUtil.isNotBlank(pmsRefund.getSupplierName()), PmsRefundEntity::getSupplierName, pmsRefund.getSupplierName())
                .eq(StrUtil.isNotBlank(pmsRefund.getType()), PmsRefundEntity::getType, pmsRefund.getType());
        return R.ok(pmsRefundService.page(page.toMpPage(), wrapper));
    }

    /**
     * 查询各退款来源的数量
     */
    @Operation(summary = "查询各退款来源的数量", description = "查询各退款来源的数量")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject PmsRefundEntity pmsRefund) {
        QueryWrapper<PmsRefundEntity> wrapper = Wrappers.query();
        wrapper.lambda().select(PmsRefundEntity::getType)
                .like(StrUtil.isNotBlank(pmsRefund.getPaymentNo()), PmsRefundEntity::getPaymentNo, pmsRefund.getPaymentNo())
                .like(StrUtil.isNotBlank(pmsRefund.getRefundNo()), PmsRefundEntity::getRefundNo, pmsRefund.getRefundNo())
                .like(StrUtil.isNotBlank(pmsRefund.getSupplierCode()), PmsRefundEntity::getSupplierCode, pmsRefund.getSupplierCode())
                .like(StrUtil.isNotBlank(pmsRefund.getSupplierName()), PmsRefundEntity::getSupplierName, pmsRefund.getSupplierName());
        return R.ok(pmsRefundService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(PmsRefundEntity::getType, Collectors.counting())));
    }

    /**
     * 通过id查询退款表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('pms_pmsAdvancePayment_view')")
    public R<PmsRefundVO> getById(@PathVariable("id") Long id) {
        return R.ok(pmsRefundService.getRefundById(id));
    }

    /**
     * 新增退款表
     *
     * @param pmsRefund 退款表
     * @return R
     */
    @Operation(summary = "新增退款表", description = "新增退款表")
    @SysLog("新增退款表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsAdvancePayment_add')")
    public R save(@RequestBody PmsRefundEntity pmsRefund) {
        return R.ok(pmsRefundService.saveRefund(pmsRefund));
    }

    /**
     * 修改退款表
     *
     * @param pmsRefund 退款表
     * @return R
     */
    @Operation(summary = "修改退款表", description = "修改退款表")
    @SysLog("修改退款表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsAdvancePayment_edit')")
    public R updateById(@RequestBody PmsRefundEntity pmsRefund) {
        return R.ok(pmsRefundService.updateById(pmsRefund));
    }

    /**
     * 通过id删除退款表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除退款表", description = "通过id删除退款表")
    @SysLog("通过id删除退款表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsAdvancePayment_del')")
    public R removeById(@RequestBody Long[] ids) {
        return R.ok(pmsRefundService.removeBatchByIds(CollUtil.toList(ids)));
    }

    /**
     * 导出excel 表格
     *
     * @param pmsRefund 查询条件
     * @param ids       导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('pms_pmsAdvancePayment_export')")
    public List<PmsRefundEntity> export(PmsRefundEntity pmsRefund, Long[] ids) {
        return pmsRefundService.list(Wrappers.lambdaQuery(pmsRefund).in(ArrayUtil.isNotEmpty(ids), PmsRefundEntity::getId, ids));
    }
}