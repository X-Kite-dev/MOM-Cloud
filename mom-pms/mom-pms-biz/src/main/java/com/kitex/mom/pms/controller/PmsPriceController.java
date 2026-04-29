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
import com.kitex.mom.pms.dto.PmsPriceDTO;
import com.kitex.mom.pms.entity.PmsPriceEntity;
import com.kitex.mom.pms.service.PmsPriceService;
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
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 价格库
 *
 * @author kitex
 * @date 2025-03-24 17:39:49
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/pmsPrice")
@Tag(description = "pmsPrice", name = "价格库管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PmsPriceController {

    private final PmsPriceService pmsPriceService;

    /**
     * 分页查询
     *
     * @param page     分页对象
     * @param pmsPrice 价格库
     * @return
     */

    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('pms_pmsPrice_view')")
    public R getPmsPricePage(@ParameterObject SearchPage page, @ParameterObject PmsPriceEntity pmsPrice) {
        QueryWrapper<PmsPriceEntity> wrapper = Wrappers.query();
        wrapper.lambda()
                .like(StrUtil.isNotBlank(pmsPrice.getSupplierCode()), PmsPriceEntity::getSupplierCode, pmsPrice.getSupplierCode())
                .like(StrUtil.isNotBlank(pmsPrice.getSupplierName()), PmsPriceEntity::getSupplierName, pmsPrice.getSupplierName())
                .like(StrUtil.isNotBlank(pmsPrice.getMatCode()), PmsPriceEntity::getMatCode, pmsPrice.getMatCode())
                .like(StrUtil.isNotBlank(pmsPrice.getMatName()), PmsPriceEntity::getMatName, pmsPrice.getMatName())
                .like(StrUtil.isNotBlank(pmsPrice.getMatSpec()), PmsPriceEntity::getMatSpec, pmsPrice.getMatSpec())
                .eq(Objects.nonNull(pmsPrice.getStatus()), PmsPriceEntity::getStatus, pmsPrice.getStatus())
                .orderByDesc(PmsPriceEntity::getCreateTime);
        return R.ok(pmsPriceService.page(page.toMpPage(), wrapper));
    }

    /**
     * 查询各价格库状态的数量
     *
     * @return
     */

    @Operation(summary = "查询各价格库状态的数量", description = "查询各价格库状态的数量")
    @GetMapping("/getCount")
    public R<Map<Boolean, Long>> getCount(@ParameterObject PmsPriceEntity pmsPrice) {
        QueryWrapper<PmsPriceEntity> wrapper = Wrappers.query();
        wrapper.lambda().select(PmsPriceEntity::getStatus)
                .like(StrUtil.isNotBlank(pmsPrice.getSupplierCode()), PmsPriceEntity::getSupplierCode, pmsPrice.getSupplierCode())
                .like(StrUtil.isNotBlank(pmsPrice.getSupplierName()), PmsPriceEntity::getSupplierName, pmsPrice.getSupplierName())
                .like(StrUtil.isNotBlank(pmsPrice.getMatCode()), PmsPriceEntity::getMatCode, pmsPrice.getMatCode())
                .like(StrUtil.isNotBlank(pmsPrice.getMatName()), PmsPriceEntity::getMatName, pmsPrice.getMatName())
                .like(StrUtil.isNotBlank(pmsPrice.getMatSpec()), PmsPriceEntity::getMatSpec, pmsPrice.getMatSpec());
        return R.ok(pmsPriceService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(PmsPriceEntity::getStatus, Collectors.counting())));
    }

    /**
     * 通过id查询价格库
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('pms_pmsPrice_view')")
    public R getById(@PathVariable("id") Long id) {
        return R.ok(pmsPriceService.getById(id));
    }

    /**
     * 通过供应商编码和物料编码查询价格库
     *
     * @param supplierCode 供应商编码
     * @param matCodes     物料编码列表
     * @return R
     */
    @Operation(summary = "通过供应商编码和物料编码查询价格库", description = "通过供应商编码和物料编码查询价格库")
    @GetMapping("/getPriceByCode")
    public R<List<PmsPriceEntity>> getPriceByCode(@RequestParam("supplierCode") String supplierCode,
                                                  @RequestParam("matCodes") List<String> matCodes) {
        return R.ok(pmsPriceService.getPriceByCode(supplierCode, matCodes));
    }

    /**
     * 新增价格库
     *
     * @param pmsPriceDTO 价格库DTO
     * @return R
     */
    @Operation(summary = "新增价格库", description = "新增价格库")
    @SysLog("新增价格库")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsPrice_add')")
    public R save(@RequestBody PmsPriceDTO pmsPriceDTO) {
        return R.ok(pmsPriceService.savePrice(pmsPriceDTO));
    }

    /**
     * 修改价格库
     *
     * @param pmsPrice 价格库
     * @return R
     */
    @Operation(summary = "修改价格库", description = "修改价格库")
    @SysLog("修改价格库")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsPrice_edit')")
    public R updateById(@RequestBody PmsPriceEntity pmsPrice) {
        return R.ok(pmsPriceService.updateById(pmsPrice));
    }

    /**
     * 通过id删除价格库
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除价格库", description = "通过id删除价格库")
    @SysLog("通过id删除价格库")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsPrice_del')")
    public R removeById(@RequestBody Long[] ids) {
        return R.ok(pmsPriceService.removeBatchByIds(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param pmsPrice 查询条件
     * @param ids      导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('pms_pmsPrice_export')")
    public List<PmsPriceEntity> export(PmsPriceEntity pmsPrice, Long[] ids) {
        return pmsPriceService.list(Wrappers.lambdaQuery(pmsPrice).in(ArrayUtil.isNotEmpty(ids), PmsPriceEntity::getId, ids));
    }
}