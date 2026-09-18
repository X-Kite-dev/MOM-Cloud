package com.kitex.mom.plan.controller;


import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.RequestExcel;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.plan.dto.TemporaryPlanQueryDTO;
import com.kitex.mom.plan.entity.PlnProductDemandEntity;
import com.kitex.mom.plan.service.PlnProductDemandService;
import com.kitex.mom.plan.vo.PlnProductDemandExcelVO;
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
 * 生产计划表
 *
 * @author kitex
 * @date 2024-11-06 17:36:16
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/planMesPlan")
@Tag(description = "planMesPlan", name = "生产计划表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PlnProductDemandController {

    private final PlnProductDemandService planMesPlanService;

    /**
     * 分页查询
     *
     * @param page        分页对象
     * @param planMesPlan 生产计划表
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('plan_planMesPlan_view')")
    public R getPlanMesPlanPage(SearchPage page, @ParameterObject PlnProductDemandEntity planMesPlan) {
        QueryWrapper<PlnProductDemandEntity> wrapper = Wrappers.query();
        wrapper.lambda()
                .like(ObjUtil.isNotEmpty(planMesPlan.getCustomerName()), PlnProductDemandEntity::getCustomerName,
                        planMesPlan.getCustomerName())
                .like(ObjUtil.isNotEmpty(planMesPlan.getCustomerCode()), PlnProductDemandEntity::getCustomerCode,
                        planMesPlan.getCustomerCode())
                .like(StrUtil.isNotBlank(planMesPlan.getSaleOrderCode()), PlnProductDemandEntity::getSaleOrderCode,
                        planMesPlan.getSaleOrderCode())
                .like(StrUtil.isNotBlank(planMesPlan.getProductName()), PlnProductDemandEntity::getProductName,
                        planMesPlan.getProductName())
                .like(StrUtil.isNotBlank(planMesPlan.getProductCode()), PlnProductDemandEntity::getProductCode,
                        planMesPlan.getProductCode())
                .eq(StrUtil.isNotBlank(planMesPlan.getDemandWarehouseCode()), PlnProductDemandEntity::getDemandWarehouseCode,
                        planMesPlan.getDemandWarehouseCode())
                .eq(StrUtil.isNotBlank(planMesPlan.getFactoryCode()), PlnProductDemandEntity::getFactoryCode,
                        planMesPlan.getFactoryCode())
                .like(StrUtil.isNotBlank(planMesPlan.getSpecification()), PlnProductDemandEntity::getSpecification,
                        planMesPlan.getSpecification())
                .eq(StrUtil.isNotBlank(planMesPlan.getPlanState()), PlnProductDemandEntity::getPlanState,
                        planMesPlan.getPlanState())
                .orderByDesc(PlnProductDemandEntity::getCreateTime);
        return R.ok(planMesPlanService.page(page.toMpPage(), wrapper));
    }

    /**
     * 查询各客户订单状态的数量
     *
     * @return
     */

    @Operation(summary = "查询各客户订单状态的数量", description = "查询各客户订单状态的数量")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject PlnProductDemandEntity planMesPlan) {
        QueryWrapper<PlnProductDemandEntity> wrapper = Wrappers.query();
        wrapper.lambda().select(PlnProductDemandEntity::getPlanState)
                .like(ObjUtil.isNotEmpty(planMesPlan.getCustomerName()), PlnProductDemandEntity::getCustomerName,
                        planMesPlan.getCustomerName())
                .like(ObjUtil.isNotEmpty(planMesPlan.getCustomerCode()), PlnProductDemandEntity::getCustomerCode,
                        planMesPlan.getCustomerCode())
                .like(StrUtil.isNotBlank(planMesPlan.getSaleOrderCode()), PlnProductDemandEntity::getSaleOrderCode,
                        planMesPlan.getSaleOrderCode())
                .like(StrUtil.isNotBlank(planMesPlan.getSpecification()), PlnProductDemandEntity::getSpecification,
                        planMesPlan.getSpecification())
                .like(StrUtil.isNotBlank(planMesPlan.getProductName()), PlnProductDemandEntity::getProductName,
                        planMesPlan.getProductName())
                .like(StrUtil.isNotBlank(planMesPlan.getProductCode()), PlnProductDemandEntity::getProductCode,
                        planMesPlan.getProductCode());
        return R.ok(planMesPlanService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(PlnProductDemandEntity::getPlanState, Collectors.counting())));
    }

    /**
     * 通过条件查询生产计划表
     *
     * @param planMesPlan 查询条件
     * @return R 对象列表
     */
    @Operation(summary = "通过条件查询", description = "通过条件查询对象")
    @GetMapping("/details")
    @PreAuthorize("@pms.hasPermission('plan_planMesPlan_view')")
    public R getDetails(@ParameterObject PlnProductDemandEntity planMesPlan) {
        return R.ok(planMesPlanService.list(Wrappers.query(planMesPlan)));
    }

    /**
     * 通过单号以及详情id查询生产计划表
     */
    @Inner
    @GetMapping("/getByOrder")
    public R getByOrder(@RequestParam("orderCode") String orderCode,
                        @RequestParam("detailsId") Long detailsId) {
        return R.ok(planMesPlanService.getOne(new LambdaQueryWrapper<PlnProductDemandEntity>()
                .eq(PlnProductDemandEntity::getSaleOrderCode, orderCode)
                .eq(PlnProductDemandEntity::getSaleOrderCodeDetails, detailsId)));
    }

    /**
     * 新增生产计划表
     *
     * @param planMesPlan 生产计划表
     * @return R
     */
    @Operation(summary = "新增生产计划表", description = "新增生产计划表")
    @SysLog("新增生产计划表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('plan_planMesPlan_add')")
    public R save(@RequestBody PlnProductDemandEntity planMesPlan) {
        return R.ok(planMesPlanService.saveManualPlan(planMesPlan));
    }

    /**
     * 新增生产计划表
     *
     * @param planMesPlan 生产计划表
     * @return R
     */
    @Inner
    @Operation(summary = "新增生产计划表", description = "新增生产计划表")
    @SysLog("新增生产计划表")
    @PostMapping("/oms/save")
    public R omsSave(@RequestBody PlnProductDemandEntity planMesPlan) {
        return R.ok(planMesPlanService.savePlan(planMesPlan));
    }

    /**
     * 更新生产计划表
     */
    @PutMapping
    @PreAuthorize("@pms.hasPermission('plan_planMesPlan_edit')")
    public R update(@RequestBody PlnProductDemandEntity planMesPlan) {
        return R.ok(planMesPlanService.updatePlan(planMesPlan));
    }

    /**
     * 更新生产计划表
     */
    @Inner
    @PutMapping("/oms/update")
    public R omsUpdate(@RequestBody PlnProductDemandEntity planMesPlan) {
        return R.ok(planMesPlanService.updateFromOms(planMesPlan));
    }

    /**
     * 更新生产计划表
     */
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_send_withdraw')")
    public R delete(@RequestBody Long[] ids) {
        return R.ok(planMesPlanService.deleteDrafts(ids));
    }

    /**
     * 导出excel 表格
     *
     * @param planMesPlan 查询条件
     * @param ids         导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('plan_planMesPlan_export')")
    public List<PlnProductDemandExcelVO> exportExcel(PlnProductDemandEntity planMesPlan, Long[] ids) {
        return planMesPlanService.exportExcel(planMesPlan, ids);

    }

    /**
     * 导入客户订单excel 表
     *
     * @param excelVOList   对象实体列表
     * @param bindingResult 错误信息列表
     * @return ok fail
     */
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('plan_planMesPlan_export')")
    public R importExcel(@RequestExcel(headRowNumber = 2) List<PlnProductDemandExcelVO> excelVOList, BindingResult bindingResult) {
        return planMesPlanService.importExcel(excelVOList, bindingResult);
    }

    /**
     * 接收生产计划
     *
     * @param ids 计划ids
     * @return
     */
    @SysLog("计划接收")
    @PutMapping("/receive")
    @PreAuthorize("@pms.hasPermission('plan_planMesPlan_receive')")
    public R<Boolean> receive(@RequestBody Long[] ids) {
        return R.ok(planMesPlanService.receive(ids));
    }

    /**
     * mrp运算
     *
     * @param ids 计划ids
     * @return
     */
    @SysLog("mrp运算")
    @PostMapping("/mrp")
    public R mrp(@RequestBody Long[] ids) {
        return planMesPlanService.mrp(ids);
    }

    /**
     * 通过条件查询需求日期
     *
     * @param templateQueryDTO 查询条件
     */
    @Operation(summary = "通过条件查询需求日期", description = "通过条件查询需求日期")
    @GetMapping("/demand/date")
    public R<List<Map<String, Object>>> getDemandDate(@ParameterObject TemporaryPlanQueryDTO templateQueryDTO) {
        return R.ok(planMesPlanService.getDemandDate(templateQueryDTO));
    }

}
