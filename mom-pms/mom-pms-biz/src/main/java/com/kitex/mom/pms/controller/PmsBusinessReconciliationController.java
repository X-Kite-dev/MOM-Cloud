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
import com.kitex.mom.pms.entity.PmsBusinessReconciliationEntity;
import com.kitex.mom.pms.service.PmsBusinessReconciliationService;
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
 * 业务对账
 *
 * @author kitex
 * @date 2025-04-07 15:01:25
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/pmsBusinessReconciliation")
@Tag(description = "pmsBusinessReconciliation", name = "业务对账管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PmsBusinessReconciliationController {

    private final PmsBusinessReconciliationService pmsBusinessReconciliationService;

    /**
     * 分页查询
     *
     * @param page                      分页对象
     * @param pmsBusinessReconciliation 业务对账
     * @return
     */

    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('pms_pmsBusinessReconciliation_view')")
    public R getPmsBusinessReconciliationPage(@ParameterObject SearchPage page, @ParameterObject PmsBusinessReconciliationEntity pmsBusinessReconciliation) {
        QueryWrapper<PmsBusinessReconciliationEntity> wrapper = Wrappers.query();
        wrapper.lambda()
                .like(StrUtil.isNotBlank(pmsBusinessReconciliation.getReconciliationNo()), PmsBusinessReconciliationEntity::getReconciliationNo, pmsBusinessReconciliation.getReconciliationNo())
                .like(StrUtil.isNotBlank(pmsBusinessReconciliation.getPmsOrderNo()), PmsBusinessReconciliationEntity::getPmsOrderNo, pmsBusinessReconciliation.getPmsOrderNo())
                .like(StrUtil.isNotBlank(pmsBusinessReconciliation.getSupplierCode()), PmsBusinessReconciliationEntity::getSupplierCode, pmsBusinessReconciliation.getSupplierCode())
                .like(StrUtil.isNotBlank(pmsBusinessReconciliation.getSupplierName()), PmsBusinessReconciliationEntity::getSupplierName, pmsBusinessReconciliation.getSupplierName())
                .like(StrUtil.isNotBlank(pmsBusinessReconciliation.getMatCode()), PmsBusinessReconciliationEntity::getMatCode, pmsBusinessReconciliation.getMatCode())
                .like(StrUtil.isNotBlank(pmsBusinessReconciliation.getMatName()), PmsBusinessReconciliationEntity::getMatName, pmsBusinessReconciliation.getMatName())
                .like(StrUtil.isNotBlank(pmsBusinessReconciliation.getMatSpec()), PmsBusinessReconciliationEntity::getMatSpec, pmsBusinessReconciliation.getMatSpec())
                .eq(Objects.nonNull(pmsBusinessReconciliation.getStatus()), PmsBusinessReconciliationEntity::getStatus, pmsBusinessReconciliation.getStatus())
                .orderByDesc(PmsBusinessReconciliationEntity::getCreateTime);
        return R.ok(pmsBusinessReconciliationService.page(page.toMpPage(), wrapper));
    }

    /**
     * 查询业务对账单各状态的数量
     */

    @Operation(summary = "查询业务对账单各状态的数量", description = "查询业务对账单各状态的数量")
    @GetMapping("/getCount")
    public R<Map<Boolean, Long>> getCount(@ParameterObject PmsBusinessReconciliationEntity pmsBusinessReconciliation) {
        QueryWrapper<PmsBusinessReconciliationEntity> wrapper = Wrappers.query();
        wrapper.lambda().select(PmsBusinessReconciliationEntity::getStatus)
                .like(StrUtil.isNotBlank(pmsBusinessReconciliation.getReconciliationNo()), PmsBusinessReconciliationEntity::getReconciliationNo, pmsBusinessReconciliation.getReconciliationNo())
                .like(StrUtil.isNotBlank(pmsBusinessReconciliation.getPmsOrderNo()), PmsBusinessReconciliationEntity::getPmsOrderNo, pmsBusinessReconciliation.getPmsOrderNo())
                .like(StrUtil.isNotBlank(pmsBusinessReconciliation.getSupplierCode()), PmsBusinessReconciliationEntity::getSupplierCode, pmsBusinessReconciliation.getSupplierCode())
                .like(StrUtil.isNotBlank(pmsBusinessReconciliation.getSupplierName()), PmsBusinessReconciliationEntity::getSupplierName, pmsBusinessReconciliation.getSupplierName())
                .like(StrUtil.isNotBlank(pmsBusinessReconciliation.getMatCode()), PmsBusinessReconciliationEntity::getMatCode, pmsBusinessReconciliation.getMatCode())
                .like(StrUtil.isNotBlank(pmsBusinessReconciliation.getMatName()), PmsBusinessReconciliationEntity::getMatName, pmsBusinessReconciliation.getMatName())
                .like(StrUtil.isNotBlank(pmsBusinessReconciliation.getMatSpec()), PmsBusinessReconciliationEntity::getMatSpec, pmsBusinessReconciliation.getMatSpec());
        return R.ok(pmsBusinessReconciliationService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(PmsBusinessReconciliationEntity::getStatus, Collectors.counting())));
    }

    /**
     * 通过id查询业务对账
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('pms_pmsBusinessReconciliation_view')")
    public R getById(@PathVariable("id") Long id) {
        return R.ok(pmsBusinessReconciliationService.getById(id));
    }

    /**
     * 新增业务对账
     *
     * @param pmsBusinessReconciliation 业务对账
     * @return R
     */
    @Operation(summary = "新增业务对账", description = "新增业务对账")
    @SysLog("新增业务对账")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsBusinessReconciliation_add')")
    public R save(@RequestBody PmsBusinessReconciliationEntity pmsBusinessReconciliation) {
        return R.ok(pmsBusinessReconciliationService.save(pmsBusinessReconciliation));
    }

    /**
     * 修改业务对账
     *
     * @param pmsBusinessReconciliation 业务对账
     * @return R
     */
    @Operation(summary = "修改业务对账", description = "修改业务对账")
    @SysLog("修改业务对账")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsBusinessReconciliation_edit')")
    public R updateById(@RequestBody PmsBusinessReconciliationEntity pmsBusinessReconciliation) {
        return R.ok(pmsBusinessReconciliationService.updateById(pmsBusinessReconciliation));
    }

    /**
     * 通过id删除业务对账
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除业务对账", description = "通过id删除业务对账")
    @SysLog("通过id删除业务对账")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsBusinessReconciliation_del')")
    public R removeById(@RequestBody Long[] ids) {
        return R.ok(pmsBusinessReconciliationService.removeBatchByIds(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param pmsBusinessReconciliation 查询条件
     * @param ids                       导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('pms_pmsBusinessReconciliation_export')")
    public List<PmsBusinessReconciliationEntity> export(PmsBusinessReconciliationEntity pmsBusinessReconciliation, Long[] ids) {
        return pmsBusinessReconciliationService.list(Wrappers.lambdaQuery(pmsBusinessReconciliation).in(ArrayUtil.isNotEmpty(ids), PmsBusinessReconciliationEntity::getId, ids));
    }
}