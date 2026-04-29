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
import com.kitex.mom.pms.entity.PmsInvoiceEntity;
import com.kitex.mom.pms.entity.PmsInvoiceReconciliationEntity;
import com.kitex.mom.pms.service.PmsInvoiceService;
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

/**
 * 采购发票
 *
 * @author kitex
 * @date 2025-04-07 17:53:31
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/pmsInvoice")
@Tag(description = "pmsInvoice", name = "采购发票管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PmsInvoiceController {

    private final PmsInvoiceService pmsInvoiceService;

    /**
     * 分页查询
     *
     * @param page       分页对象
     * @param pmsInvoice 采购发票
     * @return
     */

    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('pms_pmsInvoice_view')")
    public R getPmsInvoicePage(@ParameterObject SearchPage page, @ParameterObject PmsInvoiceEntity pmsInvoice) {
        QueryWrapper<PmsInvoiceEntity> wrapper = Wrappers.query();
        wrapper.lambda()
                .like(StrUtil.isNotBlank(pmsInvoice.getInvoiceCode()), PmsInvoiceEntity::getInvoiceCode, pmsInvoice.getInvoiceCode())
                .like(StrUtil.isNotBlank(pmsInvoice.getInvoiceNo()), PmsInvoiceEntity::getInvoiceNo, pmsInvoice.getInvoiceNo())
                .like(StrUtil.isNotBlank(pmsInvoice.getBuyer()), PmsInvoiceEntity::getBuyer, pmsInvoice.getBuyer())
                .like(StrUtil.isNotBlank(pmsInvoice.getSeller()), PmsInvoiceEntity::getSeller, pmsInvoice.getSeller())
                .orderByDesc(PmsInvoiceEntity::getUpdateTime);
        return R.ok(pmsInvoiceService.page(page.toMpPage(), wrapper));
    }

    /**
     * 通过条件查询采购发票
     *
     * @param pmsInvoice 查询条件
     * @return R  对象列表
     */
    @Operation(summary = "通过条件查询", description = "通过条件查询对象")
    @GetMapping("/details")
    @PreAuthorize("@pms.hasPermission('pms_pmsInvoice_view')")
    public R getDetails(@ParameterObject PmsInvoiceEntity pmsInvoice) {
        return R.ok(pmsInvoiceService.listDeep(Wrappers.query(pmsInvoice)));
    }

    /**
     * 根据发票id查询发票关联的业务对账单
     *
     * @param ids 发票ids
     * @return R
     */
    @Operation(summary = "根据发票id查询发票关联的业务对账单", description = "根据发票id查询发票关联的业务对账单")
    @GetMapping("/getReconciliationByInvoiceId/{ids}")
    public R<List<PmsInvoiceReconciliationEntity>> getReconciliationByInvoiceId(@PathVariable("ids") Long[] ids) {
        return R.ok(pmsInvoiceService.getReconciliationByInvoiceId(ids));
    }

    /**
     * 新增采购发票
     *
     * @param pmsInvoice 采购发票
     * @return R
     */
    @Operation(summary = "新增采购发票", description = "新增采购发票")
    @SysLog("新增采购发票")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsInvoice_add')")
    public R save(@RequestBody PmsInvoiceEntity pmsInvoice) {
        return R.ok(pmsInvoiceService.saveDeep(pmsInvoice));
    }

    /**
     * 修改采购发票
     *
     * @param pmsInvoice 采购发票
     * @return R
     */
    @Operation(summary = "修改采购发票", description = "修改采购发票")
    @SysLog("修改采购发票")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsInvoice_edit')")
    public R updateById(@RequestBody PmsInvoiceEntity pmsInvoice) {
        return R.ok(pmsInvoiceService.updateDeep(pmsInvoice));
    }

    /**
     * 通过id删除采购发票
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除采购发票", description = "通过id删除采购发票")
    @SysLog("通过id删除采购发票")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsInvoice_del')")
    public R removeById(@RequestBody Long[] ids) {
        return R.ok(pmsInvoiceService.removeDeep(ids));
    }

    /**
     * 导出excel 表格
     *
     * @param pmsInvoice 查询条件
     * @param ids        导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('pms_pmsInvoice_export')")
    public List<PmsInvoiceEntity> exportExcel(PmsInvoiceEntity pmsInvoice, Long[] ids) {
        return pmsInvoiceService.list(Wrappers.lambdaQuery(pmsInvoice).in(ArrayUtil.isNotEmpty(ids), PmsInvoiceEntity::getId, ids));
    }

    /**
     * 导入excel 表
     *
     * @param pmsInvoiceList 对象实体列表
     * @param bindingResult  错误信息列表
     * @return ok fail
     */
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('pms_pmsInvoice_export')")
    public R importExcel(@RequestExcel List<PmsInvoiceEntity> pmsInvoiceList, BindingResult bindingResult) {
        return R.ok(pmsInvoiceService.saveBatch(pmsInvoiceList));
    }
}