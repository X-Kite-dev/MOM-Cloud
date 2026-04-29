package com.kitex.mom.pms.controller;


import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.RequestExcel;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.plan.dto.TemporaryPlanQueryDTO;
import com.kitex.mom.pms.dto.PmsOrderSupplierQueryDTO;
import com.kitex.mom.pms.dto.PmsPlanQueryDTO;
import com.kitex.mom.pms.entity.PmsPlanEntity;
import com.kitex.mom.pms.enums.PmsPlanStatusEnum;
import com.kitex.mom.pms.service.PmsPlanService;
import com.kitex.mom.pms.vo.PmsPlanExcelVO;
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
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 采购计划表
 *
 * @author kitex
 * @date 2025-01-07 09:42:33
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/pmsPlan")
@Tag(description = "pmsPlan", name = "采购计划表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PmsPlanController {

    private final PmsPlanService pmsPlanService;

    /**
     * 分页查询采购计划
     *
     * @param page    分页对象
     * @param pmsPlan 采购计划表
     * @return
     */

    @Operation(summary = "分页查询采购计划", description = "分页查询采购计划")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('pms_pmsPlan_view')")
    public R<Page<PmsPlanEntity>> getPmsPlanPage(@ParameterObject SearchPage page, @ParameterObject PmsPlanQueryDTO pmsPlan) {
        QueryWrapper<PmsPlanEntity> wrapper = Wrappers.query();
        wrapper.lambda()
                .like(StrUtil.isNotBlank(pmsPlan.getPmsPlanNo()), PmsPlanEntity::getPmsPlanNo, pmsPlan.getPmsPlanNo())
                .like(StrUtil.isNotBlank(pmsPlan.getMatCode()), PmsPlanEntity::getMatCode, pmsPlan.getMatCode())
                .like(StrUtil.isNotBlank(pmsPlan.getMatName()), PmsPlanEntity::getMatName, pmsPlan.getMatName())
                .like(StrUtil.isNotBlank(pmsPlan.getMatSpec()), PmsPlanEntity::getMatSpec, pmsPlan.getMatSpec())
                .eq(StrUtil.isNotBlank(pmsPlan.getStatus()), PmsPlanEntity::getStatus, pmsPlan.getStatus())
                .like(StrUtil.isNotBlank(pmsPlan.getOrderNo()), PmsPlanEntity::getOrderNo, pmsPlan.getOrderNo())
                .orderByAsc(PmsPlanEntity::getStatus).orderByDesc(PmsPlanEntity::getCreateTime);
        if (ArrayUtil.isNotEmpty(pmsPlan.getPlanDeliveryTime())) {
            wrapper.lambda().between(PmsPlanEntity::getPlanDeliveryTime, pmsPlan.getPlanDeliveryTime()[0],
                    pmsPlan.getPlanDeliveryTime()[1]);
        }
        return R.ok(pmsPlanService.page(page.toMpPage(), wrapper));
    }

    /**
     * 查询各采购计划状态的数量
     *
     * @return
     */

    @Operation(summary = "查询各采购计划状态的数量", description = "查询各采购计划状态的数量")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject PmsPlanQueryDTO pmsPlan) {
        QueryWrapper<PmsPlanEntity> wrapper = Wrappers.query();
        wrapper.lambda().select(PmsPlanEntity::getStatus)
                .like(StrUtil.isNotBlank(pmsPlan.getPmsPlanNo()), PmsPlanEntity::getPmsPlanNo, pmsPlan.getPmsPlanNo())
                .like(StrUtil.isNotBlank(pmsPlan.getMatCode()), PmsPlanEntity::getMatCode, pmsPlan.getMatCode())
                .like(StrUtil.isNotBlank(pmsPlan.getMatName()), PmsPlanEntity::getMatName, pmsPlan.getMatName())
                .like(StrUtil.isNotBlank(pmsPlan.getMatSpec()), PmsPlanEntity::getMatSpec, pmsPlan.getMatSpec())
                .like(StrUtil.isNotBlank(pmsPlan.getOrderNo()), PmsPlanEntity::getOrderNo, pmsPlan.getOrderNo());
        if (ArrayUtil.isNotEmpty(pmsPlan.getPlanDeliveryTime())) {
            wrapper.lambda().between(PmsPlanEntity::getPlanDeliveryTime, pmsPlan.getPlanDeliveryTime()[0],
                    pmsPlan.getPlanDeliveryTime()[1]);
        }
        return R.ok(pmsPlanService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(PmsPlanEntity::getStatus, Collectors.counting())));
    }

    /**
     * 根据供应商供货物料分页查询采购计划
     *
     * @param page    分页对象
     * @param pmsPlan 采购计划表
     * @return
     */
    @Operation(summary = "根据供应商供货物料分页查询采购计划", description = "根据供应商供货物料分页查询采购计划")
    @GetMapping("/orderPage")
    public R<Page<PmsPlanEntity>> getOrderPage(@ParameterObject SearchPage page, @ParameterObject PmsOrderSupplierQueryDTO pmsPlan) {
        LambdaQueryWrapper<PmsPlanEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(pmsPlan.getPmsPlanNo()), PmsPlanEntity::getPmsPlanNo, pmsPlan.getPmsPlanNo());
        wrapper.like(StrUtil.isNotBlank(pmsPlan.getMatCode()), PmsPlanEntity::getMatCode, pmsPlan.getMatCode());
        wrapper.like(StrUtil.isNotBlank(pmsPlan.getMatName()), PmsPlanEntity::getMatName, pmsPlan.getMatName());
        wrapper.in(ObjUtil.isNotEmpty(pmsPlan.getMatCodes()), PmsPlanEntity::getMatCode, pmsPlan.getMatCodes());
        if (ArrayUtil.isNotEmpty(pmsPlan.getPlanDeliveryTime())) {
            wrapper.between(PmsPlanEntity::getPlanDeliveryTime, pmsPlan.getPlanDeliveryTime()[0],
                    pmsPlan.getPlanDeliveryTime()[1]);
        }
        wrapper.in(PmsPlanEntity::getStatus, PmsPlanStatusEnum.NO_CREATEK.getCode(), PmsPlanStatusEnum.CREATEING.getCode());
        wrapper.orderByDesc(PmsPlanEntity::getCreateTime);
        return R.ok(pmsPlanService.page(page.toMpPage(), wrapper));
    }

    /**
     * 通过id查询采购计划表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('pms_pmsPlan_view')")
    public R<PmsPlanEntity> getById(@PathVariable("id") Long id) {
        return R.ok(pmsPlanService.getById(id));
    }

    /**
     * 新增采购计划表
     *
     * @param pmsPlan 采购计划表
     * @return R
     */
    @Operation(summary = "新增采购计划表", description = "新增采购计划表")
    @SysLog("新增采购计划表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsPlan_add')")
    public R<Boolean> save(@RequestBody PmsPlanEntity pmsPlan) {
        return R.ok(pmsPlanService.savePlan(pmsPlan));
    }

    /**
     * 修改采购计划表
     *
     * @param pmsPlan 采购计划表
     * @return R
     */
    @Operation(summary = "修改采购计划表", description = "修改采购计划表")
    @SysLog("修改采购计划表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsPlan_edit')")
    public R<Boolean> updateById(@RequestBody PmsPlanEntity pmsPlan) {
        return R.ok(pmsPlanService.updateById(pmsPlan));
    }

    /**
     * 通过id删除采购计划表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除采购计划表", description = "通过id删除采购计划表")
    @SysLog("通过id删除采购计划表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsPlan_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(pmsPlanService.removeBatchByIds(CollUtil.toList(ids)));
    }

    /**
     * 导入excel 表格
     *
     * @param excelVOList   采购计划列表
     * @param bindingResult 错误信息列表
     * @param factoryCode   所属工厂编码
     * @return R
     */
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('pms_pmsPlan_export')")
    public R importPmsPlan(@RequestExcel(headRowNumber = 2) List<PmsPlanExcelVO> excelVOList, BindingResult bindingResult,
                           @RequestParam("factoryCode") Long factoryCode) {
        return pmsPlanService.importPmsPlan(excelVOList, bindingResult, String.valueOf(factoryCode));
    }

    /**
     * 导出excel 表格
     *
     * @param pmsPlan 查询条件
     * @param ids     导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('pms_pmsPlan_export')")
    public List<PmsPlanExcelVO> export(PmsPlanEntity pmsPlan, Long[] ids) {
        return pmsPlanService.export(pmsPlan, ids);
    }

    /**
     * 通过时间和物料编码查询采购计划表
     *
     * @param templateQueryDTO 查询条件
     * @return R
     */
    @Operation(summary = "通过时间和物料编码查询采购计划表", description = "通过时间和物料编码查询采购计划表")
    @GetMapping("/plnDemand")
    public R<List<Map<String, String>>> getPlnDemand(@ParameterObject TemporaryPlanQueryDTO templateQueryDTO) {
        return R.ok(pmsPlanService.getPlnDemand(templateQueryDTO));
    }

    /**
     * 根据采购计划下达号查询采购计划
     *
     * @param orderNo 采购计划下达号
     * @return R
     */
    @Operation(summary = "根据采购计划下达号查询采购计划", description = "根据采购计划下达号查询采购计划")
    @GetMapping("/omsLife")
    public R<List<PmsPlanEntity>> omsLife(@RequestParam("orderNo") String orderNo) {
        return R.ok(pmsPlanService.omsLife(orderNo));
    }

}