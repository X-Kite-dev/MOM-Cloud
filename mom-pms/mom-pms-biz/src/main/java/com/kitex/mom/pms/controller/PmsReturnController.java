package com.kitex.mom.pms.controller;


import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.pms.entity.PmsReturnEntity;
import com.kitex.mom.pms.service.PmsReturnService;
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
 * 采购退货
 *
 * @author kitex
 * @date 2025-02-24 08:38:40
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/pmsReturn")
@Tag(description = "pmsReturn", name = "采购退货管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PmsReturnController {

    private final PmsReturnService pmsReturnService;

    /**
     * 分页查询采购退货
     *
     * @param page      分页对象
     * @param pmsReturn 采购退货
     * @return
     */

    @Operation(summary = "分页查询采购退货", description = "分页查询采购退货")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('pms_pmsReturn_view')")
    public R<Page<PmsReturnEntity>> getPmsReturnPage(@ParameterObject SearchPage page, @ParameterObject PmsReturnEntity pmsReturn) {
        QueryWrapper<PmsReturnEntity> wrapper = Wrappers.query();
        wrapper.lambda()
                .like(StrUtil.isNotBlank(pmsReturn.getReturnNo()), PmsReturnEntity::getReturnNo, pmsReturn.getReturnNo())
                .like(StrUtil.isNotBlank(pmsReturn.getOrderNo()), PmsReturnEntity::getOrderNo, pmsReturn.getOrderNo())
                .like(StrUtil.isNotBlank(pmsReturn.getSupplierCode()), PmsReturnEntity::getSupplierCode, pmsReturn.getSupplierCode())
                .like(StrUtil.isNotBlank(pmsReturn.getSupplierName()), PmsReturnEntity::getSupplierName, pmsReturn.getSupplierName())
                .orderByDesc(PmsReturnEntity::getCreateTime);
        return R.ok(pmsReturnService.page(page.toMpPage(), wrapper));
    }

    /**
     * 通过条件查询采购退货
     *
     * @param pmsReturn 查询条件
     * @return R  对象列表
     */
    @Operation(summary = "通过条件查询", description = "通过条件查询对象")
    @GetMapping("/details")
    @PreAuthorize("@pms.hasPermission('pms_pmsReturn_view')")
    public R<List<PmsReturnEntity>> getDetails(@ParameterObject PmsReturnEntity pmsReturn) {
        return R.ok(pmsReturnService.listDeep(Wrappers.query(pmsReturn)));
    }

    /**
     * 新增采购退货
     *
     * @param pmsReturn 采购退货
     * @return R
     */
    @Operation(summary = "新增采购退货", description = "新增采购退货")
    @SysLog("新增采购退货")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsReturn_add')")
    public R<Boolean> save(@RequestBody PmsReturnEntity pmsReturn) {
        return R.ok(pmsReturnService.saveDeep(pmsReturn));
    }

    /**
     * 修改采购退货
     *
     * @param pmsReturn 采购退货
     * @return R
     */
    @Operation(summary = "修改采购退货", description = "修改采购退货")
    @SysLog("修改采购退货")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsReturn_edit')")
    public R<Boolean> updateById(@RequestBody PmsReturnEntity pmsReturn) {
        return R.ok(pmsReturnService.updateDeep(pmsReturn));
    }

    /**
     * 修改状态
     *
     * @param pmsReturn 采购退货
     * @return R
     */
    @Operation(summary = "修改状态", description = "修改状态")
    @SysLog("修改状态")
    @PutMapping("/statusChange")
    public R<Boolean> statusChange(@RequestBody PmsReturnEntity pmsReturn) {
        return R.ok(pmsReturnService.updateById(pmsReturn));
    }

    /**
     * 通过id删除采购退货
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除采购退货", description = "通过id删除采购退货")
    @SysLog("通过id删除采购退货")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsReturn_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(pmsReturnService.removeDeep(ids));
    }

    /**
     * 导出excel 表格
     *
     * @param pmsReturn 查询条件
     * @param ids       导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('pms_pmsReturn_export')")
    public List<PmsReturnEntity> exportExcel(PmsReturnEntity pmsReturn, Long[] ids) {
        return pmsReturnService.list(Wrappers.lambdaQuery(pmsReturn).in(ArrayUtil.isNotEmpty(ids), PmsReturnEntity::getId, ids));
    }
}