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
import com.kitex.mom.pms.entity.PmsHistoryPriceEntity;
import com.kitex.mom.pms.service.PmsHistoryPriceService;
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
 * 历史采购价表
 *
 * @author kitex
 * @date 2025-03-25 11:05:14
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/pmsHistoryPrice")
@Tag(description = "pmsHistoryPrice", name = "历史采购价表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PmsHistoryPriceController {

    private final PmsHistoryPriceService pmsHistoryPriceService;

    /**
     * 分页查询
     *
     * @param page            分页对象
     * @param pmsHistoryPrice 历史采购价表
     * @return
     */

    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('pms_pmsHistoryPrice_view')")
    public R getPmsHistoryPricePage(@ParameterObject SearchPage page, @ParameterObject PmsHistoryPriceEntity pmsHistoryPrice) {
        QueryWrapper<PmsHistoryPriceEntity> wrapper = Wrappers.query();
        wrapper.lambda()
                .like(StrUtil.isNotBlank(pmsHistoryPrice.getPmsOrderNo()), PmsHistoryPriceEntity::getPmsOrderNo, pmsHistoryPrice.getPmsOrderNo())
                .like(StrUtil.isNotBlank(pmsHistoryPrice.getSupplierCode()), PmsHistoryPriceEntity::getSupplierCode, pmsHistoryPrice.getSupplierCode())
                .like(StrUtil.isNotBlank(pmsHistoryPrice.getSupplierName()), PmsHistoryPriceEntity::getSupplierName, pmsHistoryPrice.getSupplierName())
                .like(StrUtil.isNotBlank(pmsHistoryPrice.getMatCode()), PmsHistoryPriceEntity::getMatCode, pmsHistoryPrice.getMatCode())
                .like(StrUtil.isNotBlank(pmsHistoryPrice.getMatName()), PmsHistoryPriceEntity::getMatName, pmsHistoryPrice.getMatName())
                .like(StrUtil.isNotBlank(pmsHistoryPrice.getMatSpec()), PmsHistoryPriceEntity::getMatSpec, pmsHistoryPrice.getMatSpec())
                .orderByDesc(PmsHistoryPriceEntity::getCreateTime);
        return R.ok(pmsHistoryPriceService.page(page.toMpPage(), wrapper));
    }


    /**
     * 通过id查询历史采购价表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('pms_pmsHistoryPrice_view')")
    public R getById(@PathVariable("id") Long id) {
        return R.ok(pmsHistoryPriceService.getById(id));
    }

    /**
     * 新增历史采购价表
     *
     * @param pmsHistoryPrice 历史采购价表
     * @return R
     */
    @Operation(summary = "新增历史采购价表", description = "新增历史采购价表")
    @SysLog("新增历史采购价表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsHistoryPrice_add')")
    public R save(@RequestBody PmsHistoryPriceEntity pmsHistoryPrice) {
        return R.ok(pmsHistoryPriceService.save(pmsHistoryPrice));
    }

    /**
     * 修改历史采购价表
     *
     * @param pmsHistoryPrice 历史采购价表
     * @return R
     */
    @Operation(summary = "修改历史采购价表", description = "修改历史采购价表")
    @SysLog("修改历史采购价表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsHistoryPrice_edit')")
    public R updateById(@RequestBody PmsHistoryPriceEntity pmsHistoryPrice) {
        return R.ok(pmsHistoryPriceService.updateById(pmsHistoryPrice));
    }

    /**
     * 通过id删除历史采购价表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除历史采购价表", description = "通过id删除历史采购价表")
    @SysLog("通过id删除历史采购价表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsHistoryPrice_del')")
    public R removeById(@RequestBody Long[] ids) {
        return R.ok(pmsHistoryPriceService.removeBatchByIds(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param pmsHistoryPrice 查询条件
     * @param ids             导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('pms_pmsHistoryPrice_export')")
    public List<PmsHistoryPriceEntity> export(PmsHistoryPriceEntity pmsHistoryPrice, Long[] ids) {
        return pmsHistoryPriceService.list(Wrappers.lambdaQuery(pmsHistoryPrice).in(ArrayUtil.isNotEmpty(ids), PmsHistoryPriceEntity::getId, ids));
    }
}