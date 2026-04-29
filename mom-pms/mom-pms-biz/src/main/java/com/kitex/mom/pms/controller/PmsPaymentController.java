package com.kitex.mom.pms.controller;


import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.RequestExcel;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.pms.entity.PmsPaymentEntity;
import com.kitex.mom.pms.service.PmsPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 付款
 *
 * @author kitex
 * @date 2025-04-08 11:08:12
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/pmsPayment")
@Tag(description = "pmsPayment", name = "付款管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PmsPaymentController {

    private final PmsPaymentService pmsPaymentService;

    /**
     * 分页查询
     *
     * @param page              分页对象
     * @param pmsAdvancePayment 预付款
     * @return
     */

    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('pms_pmsAdvancePayment_view')")
    public R getPmsAdvancePaymentPage(@ParameterObject SearchPage page, @ParameterObject PmsPaymentEntity pmsAdvancePayment) {
        QueryWrapper<PmsPaymentEntity> wrapper = Wrappers.query();
        wrapper.lambda()
                .like(StrUtil.isNotBlank(pmsAdvancePayment.getPaymentNo()), PmsPaymentEntity::getPaymentNo, pmsAdvancePayment.getPaymentNo())
                .like(StrUtil.isNotBlank(pmsAdvancePayment.getSupplierName()), PmsPaymentEntity::getSupplierName, pmsAdvancePayment.getSupplierName())
                .like(StrUtil.isNotBlank(pmsAdvancePayment.getSupplierCode()), PmsPaymentEntity::getSupplierCode, pmsAdvancePayment.getSupplierCode())
                .eq(StrUtil.isNotBlank(pmsAdvancePayment.getStatus()), PmsPaymentEntity::getStatus, pmsAdvancePayment.getStatus())
                .eq(StrUtil.isNotBlank(pmsAdvancePayment.getType()), PmsPaymentEntity::getType, pmsAdvancePayment.getType());
        return R.ok(pmsPaymentService.page(page.toMpPage(), wrapper));
    }

    /**
     * 查询预付款各状态的数量
     */

    @Operation(summary = "查询预付款各状态的数量", description = "查询预付款各状态的数量")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject PmsPaymentEntity pmsAdvancePayment) {
        QueryWrapper<PmsPaymentEntity> wrapper = Wrappers.query();
        wrapper.lambda().select(PmsPaymentEntity::getStatus)
                .like(StrUtil.isNotBlank(pmsAdvancePayment.getPaymentNo()), PmsPaymentEntity::getPaymentNo, pmsAdvancePayment.getPaymentNo())
                .like(StrUtil.isNotBlank(pmsAdvancePayment.getSupplierName()), PmsPaymentEntity::getSupplierName, pmsAdvancePayment.getSupplierName())
                .like(StrUtil.isNotBlank(pmsAdvancePayment.getSupplierCode()), PmsPaymentEntity::getSupplierCode, pmsAdvancePayment.getSupplierCode())
                .eq(StrUtil.isNotBlank(pmsAdvancePayment.getType()), PmsPaymentEntity::getType, pmsAdvancePayment.getType());
        return R.ok(pmsPaymentService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(PmsPaymentEntity::getStatus, Collectors.counting())));
    }

    /**
     * 通过条件查询预付款
     *
     * @param pmsAdvancePayment 查询条件
     * @return R  对象列表
     */
    @Operation(summary = "通过条件查询", description = "通过条件查询对象")
    @GetMapping("/details")
    @PreAuthorize("@pms.hasPermission('pms_pmsAdvancePayment_view')")
    public R getDetails(@ParameterObject PmsPaymentEntity pmsAdvancePayment) {
        return R.ok(pmsPaymentService.listDeep(Wrappers.query(pmsAdvancePayment)));
    }

    /**
     * 查询预付款和票到付款的付款金额总数和退款金额总数
     */

    @Operation(summary = "查询预付款和票到付款的付款金额总数和退款金额总数", description = "查询预付款和票到付款的付款金额总数和退款金额总数")
    @GetMapping("/getAmount")
    public R<Map<String, BigDecimal>> getAmount(@ParameterObject PmsPaymentEntity pmsAdvancePayment) {
        return R.ok(pmsPaymentService.getAmount(pmsAdvancePayment));
    }

    /**
     * 新增预付款
     *
     * @param pmsAdvancePayment 预付款
     * @return R
     */
    @Operation(summary = "新增预付款", description = "新增预付款")
    @SysLog("新增预付款")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsAdvancePayment_add')")
    public R save(@RequestBody PmsPaymentEntity pmsAdvancePayment) {
        return R.ok(pmsPaymentService.saveDeep(pmsAdvancePayment));
    }

    /**
     * 修改预付款
     *
     * @param pmsAdvancePayment 预付款
     * @return R
     */
    @Operation(summary = "修改预付款", description = "修改预付款")
    @SysLog("修改预付款")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsAdvancePayment_edit')")
    public R updateById(@RequestBody PmsPaymentEntity pmsAdvancePayment) {
        return R.ok(pmsPaymentService.updateDeep(pmsAdvancePayment));
    }

    /**
     * 通过id删除预付款
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除预付款", description = "通过id删除预付款")
    @SysLog("通过id删除预付款")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsAdvancePayment_del')")
    public R removeById(@RequestBody Long[] ids) {
        return R.ok(pmsPaymentService.removeDeep(ids));
    }

    /**
     * 导出excel 表格
     *
     * @param pmsAdvancePayment 查询条件
     * @param ids               导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('pms_pmsAdvancePayment_export')")
    public List<PmsPaymentEntity> exportExcel(PmsPaymentEntity pmsAdvancePayment, Long[] ids) {
        return pmsPaymentService.list(Wrappers.lambdaQuery(pmsAdvancePayment).in(ArrayUtil.isNotEmpty(ids), PmsPaymentEntity::getId, ids));
    }

    /**
     * 导入excel 表
     *
     * @param pmsAdvancePaymentList 对象实体列表
     * @param bindingResult         错误信息列表
     * @return ok fail
     */
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('pms_pmsAdvancePayment_export')")
    public R importExcel(@RequestExcel List<PmsPaymentEntity> pmsAdvancePaymentList, BindingResult bindingResult) {
        return R.ok(pmsPaymentService.saveBatch(pmsAdvancePaymentList));
    }
}