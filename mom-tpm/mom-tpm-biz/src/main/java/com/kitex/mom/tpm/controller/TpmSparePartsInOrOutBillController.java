package com.kitex.mom.tpm.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.tpm.dto.TpmSparePartsInOrOutBillDto;
import com.kitex.mom.tpm.entity.TpmSparePartsInOrOutBillEntity;
import com.kitex.mom.tpm.enums.BillOperateTypeEnum;
import com.kitex.mom.tpm.service.TpmSparePartsInOrOutBillService;
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
 * 备件 入/出库单
 *
 * @author kitex
 * @date 2025-02-11 16:42:13
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmSparePartsInOrOutBill")
@Tag(description = "tpmSparePartsInOrOutBill", name = "备件 入/出库单管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmSparePartsInOrOutBillController {

    private final TpmSparePartsInOrOutBillService tpmSparePartsInOrOutBillService;

    /**
     * 分页查询
     *
     * @param page                     分页对象
     * @param tpmSparePartsInOrOutBill 备件 入/出库单
     * @return
     */
    @Operation(summary = "备件出库单分页查询", description = "分页查询备件出库单列表")
    @GetMapping("/out/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsInOrOutBill_view')")
    public R<Page<TpmSparePartsInOrOutBillEntity>> getTpmSparePartsOutBillPage(@ParameterObject SearchPage page, @ParameterObject TpmSparePartsInOrOutBillEntity tpmSparePartsInOrOutBill) {
        LambdaQueryWrapper<TpmSparePartsInOrOutBillEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(TpmSparePartsInOrOutBillEntity::getOperateType, BillOperateTypeEnum.OUT.getCode());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsInOrOutBill.getSparePartsCode()), TpmSparePartsInOrOutBillEntity::getSparePartsCode, tpmSparePartsInOrOutBill.getSparePartsCode());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsInOrOutBill.getSparePartsName()), TpmSparePartsInOrOutBillEntity::getSparePartsName, tpmSparePartsInOrOutBill.getSparePartsName());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsInOrOutBill.getStoreroomName()), TpmSparePartsInOrOutBillEntity::getStoreroomName, tpmSparePartsInOrOutBill.getStoreroomName());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsInOrOutBill.getLocationName()), TpmSparePartsInOrOutBillEntity::getLocationName, tpmSparePartsInOrOutBill.getLocationName());
        wrapper.eq(StrUtil.isNotBlank(tpmSparePartsInOrOutBill.getState()), TpmSparePartsInOrOutBillEntity::getState, tpmSparePartsInOrOutBill.getState());
        wrapper.orderByAsc(TpmSparePartsInOrOutBillEntity::getState);
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return R.ok(tpmSparePartsInOrOutBillService.page(page.toMpPage(), wrapper));
    }

    /**
     * 分页查询 - 出库统计
     */
    @Operation(summary = "分页查询 - 出库统计", description = "分页查询 - 出库统计")
    @GetMapping("/out/getCount")
    public R<Map<String, Long>> outGetCount(@ParameterObject TpmSparePartsInOrOutBillEntity tpmSparePartsInOrOutBill) {
        LambdaQueryWrapper<TpmSparePartsInOrOutBillEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(TpmSparePartsInOrOutBillEntity::getOperateType, BillOperateTypeEnum.OUT.getCode());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsInOrOutBill.getSparePartsCode()), TpmSparePartsInOrOutBillEntity::getSparePartsCode, tpmSparePartsInOrOutBill.getSparePartsCode());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsInOrOutBill.getSparePartsName()), TpmSparePartsInOrOutBillEntity::getSparePartsName, tpmSparePartsInOrOutBill.getSparePartsName());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsInOrOutBill.getStoreroomName()), TpmSparePartsInOrOutBillEntity::getStoreroomName, tpmSparePartsInOrOutBill.getStoreroomName());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsInOrOutBill.getLocationName()), TpmSparePartsInOrOutBillEntity::getLocationName, tpmSparePartsInOrOutBill.getLocationName());
        return R.ok(tpmSparePartsInOrOutBillService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(TpmSparePartsInOrOutBillEntity::getState, Collectors.counting())));
    }

    @Operation(summary = "备件入库单分页查询", description = "分页查询备件入库单列表")
    @GetMapping("/in/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsInOrOutBill_view')")
    public R<Page<TpmSparePartsInOrOutBillEntity>> getTpmSparePartsInBillPage(@ParameterObject SearchPage page, @ParameterObject TpmSparePartsInOrOutBillEntity tpmSparePartsInOrOutBill) {
        LambdaQueryWrapper<TpmSparePartsInOrOutBillEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(TpmSparePartsInOrOutBillEntity::getOperateType, BillOperateTypeEnum.IN.getCode());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsInOrOutBill.getSparePartsCode()), TpmSparePartsInOrOutBillEntity::getSparePartsCode, tpmSparePartsInOrOutBill.getSparePartsCode());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsInOrOutBill.getSparePartsName()), TpmSparePartsInOrOutBillEntity::getSparePartsName, tpmSparePartsInOrOutBill.getSparePartsName());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsInOrOutBill.getStoreroomName()), TpmSparePartsInOrOutBillEntity::getStoreroomName, tpmSparePartsInOrOutBill.getStoreroomName());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsInOrOutBill.getLocationName()), TpmSparePartsInOrOutBillEntity::getLocationName, tpmSparePartsInOrOutBill.getLocationName());
        wrapper.eq(StrUtil.isNotBlank(tpmSparePartsInOrOutBill.getState()), TpmSparePartsInOrOutBillEntity::getState, tpmSparePartsInOrOutBill.getState());
        wrapper.orderByAsc(TpmSparePartsInOrOutBillEntity::getState);
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return R.ok(tpmSparePartsInOrOutBillService.page(page.toMpPage(), wrapper));
    }

    /**
     * 分页查询 - 入库统计
     */
    @Operation(summary = "分页查询 - 入库统计", description = "分页查询 - 入库统计")
    @GetMapping("/in/getCount")
    public R<Map<String, Long>> inGetCount(@ParameterObject TpmSparePartsInOrOutBillEntity tpmSparePartsInOrOutBill) {
        LambdaQueryWrapper<TpmSparePartsInOrOutBillEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(TpmSparePartsInOrOutBillEntity::getOperateType, BillOperateTypeEnum.IN.getCode());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsInOrOutBill.getSparePartsCode()), TpmSparePartsInOrOutBillEntity::getSparePartsCode, tpmSparePartsInOrOutBill.getSparePartsCode());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsInOrOutBill.getSparePartsName()), TpmSparePartsInOrOutBillEntity::getSparePartsName, tpmSparePartsInOrOutBill.getSparePartsName());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsInOrOutBill.getStoreroomName()), TpmSparePartsInOrOutBillEntity::getStoreroomName, tpmSparePartsInOrOutBill.getStoreroomName());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsInOrOutBill.getLocationName()), TpmSparePartsInOrOutBillEntity::getLocationName, tpmSparePartsInOrOutBill.getLocationName());
        return R.ok(tpmSparePartsInOrOutBillService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(TpmSparePartsInOrOutBillEntity::getState, Collectors.counting())));
    }


    /**
     * 通过id查询备件 入/出库单
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "查询出入库单详情", description = "通过id查询备件出入库单详情")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsInOrOutBill_view')")
    public R<TpmSparePartsInOrOutBillEntity> getById(@PathVariable("id") Long id) {
        return R.ok(tpmSparePartsInOrOutBillService.getById(id));
    }

    /**
     * 新增备件 入/出库单
     *
     * @param tpmSparePartsInOrOutBillDto 备件 入/出库单
     * @return R
     */
    @Operation(summary = "新增备件出入库单", description = "新增备件入库或出库单")
    @SysLog("新增备件 入/出库单")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsInOrOutBill_add')")
    public R<Boolean> saveBill(@RequestBody TpmSparePartsInOrOutBillDto tpmSparePartsInOrOutBillDto) {
        return R.ok(tpmSparePartsInOrOutBillService.saveBill(tpmSparePartsInOrOutBillDto));
    }


    /**
     * 出入库处理
     */
    @Operation(summary = "处理出入库单", description = "处理备件出入库操作")
    @GetMapping("/handle")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsInOrOutBill_handle')")
    public R<Boolean> handle(@RequestParam("ids") List<Long> ids, @RequestParam("type") String type) {
        return R.ok(tpmSparePartsInOrOutBillService.handle(ids, type));
    }


    /**
     * 修改备件 入/出库单
     *
     * @param tpmSparePartsInOrOutBill 备件 入/出库单
     * @return R
     */
    @Operation(summary = "修改出入库单", description = "修改备件出入库单信息")
    @SysLog("修改备件 入/出库单")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsInOrOutBill_edit')")
    public R<Boolean> updateById(@RequestBody TpmSparePartsInOrOutBillEntity tpmSparePartsInOrOutBill) {
        return R.ok(tpmSparePartsInOrOutBillService.updateById(tpmSparePartsInOrOutBill));
    }

    /**
     * 通过id删除备件 入/出库单
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "删除出入库单", description = "通过id删除备件出入库单")
    @SysLog("通过id删除备件 入/出库单")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsInOrOutBill_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(tpmSparePartsInOrOutBillService.removeBatchByIds(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param tpmSparePartsInOrOutBill 查询条件
     * @param ids                      导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsInOrOutBill_export')")
    public List<TpmSparePartsInOrOutBillEntity> export(TpmSparePartsInOrOutBillEntity tpmSparePartsInOrOutBill, Long[] ids) {
        return tpmSparePartsInOrOutBillService.list(Wrappers.lambdaQuery(tpmSparePartsInOrOutBill).in(ArrayUtil.isNotEmpty(ids), TpmSparePartsInOrOutBillEntity::getId, ids));
    }
}