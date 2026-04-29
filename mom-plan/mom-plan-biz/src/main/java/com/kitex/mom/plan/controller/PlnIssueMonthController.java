package com.kitex.mom.plan.controller;


import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.plan.dto.MonthPlanQueryDTO;
import com.kitex.mom.plan.dto.PlnIssueDTO;
import com.kitex.mom.plan.dto.TemporaryPlanQueryDTO;
import com.kitex.mom.plan.entity.PlnIssueMonthEntity;
import com.kitex.mom.plan.service.PlnIssueMonthService;
import com.kitex.mom.plan.vo.PlnIssueMonthVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 采购排产结果管理
 *
 * @author kitex
 * @date 2025-02-10 16:11:29
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/plnIssueMonth")
@Tag(description = "plnIssueMonth", name = "采购排产结果管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PlnIssueMonthController {

    private final PlnIssueMonthService plnIssueMonthService;

    /**
     * 分页查询采购排产结果
     *
     * @param page          分页对象
     * @param plnIssueMonth 采购排产结果表
     * @return
     */

    @Operation(summary = "分页查询采购排产结果", description = "分页查询采购排产结果")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('plan_plnIssueMonth_view')")
    public R getPlnIssueMonthPage(@ParameterObject SearchPage page, @ParameterObject PlnIssueDTO plnIssueMonth) {
        QueryWrapper<PlnIssueMonthEntity> wrapper = Wrappers.query();
        wrapper.lambda()
                .like(StrUtil.isNotBlank(plnIssueMonth.getNo()), PlnIssueMonthEntity::getIssueMonthNo, plnIssueMonth.getNo())
                .like(StrUtil.isNotBlank(plnIssueMonth.getCustomerCode()), PlnIssueMonthEntity::getCustomerCode, plnIssueMonth.getCustomerCode())
                .like(StrUtil.isNotBlank(plnIssueMonth.getCustomerName()), PlnIssueMonthEntity::getCustomerName, plnIssueMonth.getCustomerName())
                .like(StrUtil.isNotBlank(plnIssueMonth.getProductCode()), PlnIssueMonthEntity::getProductCode, plnIssueMonth.getProductCode())
                .like(StrUtil.isNotBlank(plnIssueMonth.getProductName()), PlnIssueMonthEntity::getProductName, plnIssueMonth.getProductName())
                .like(StrUtil.isNotBlank(plnIssueMonth.getSpecificationModel()), PlnIssueMonthEntity::getSpecificationModel, plnIssueMonth.getSpecificationModel())
                .eq(StrUtil.isNotBlank(plnIssueMonth.getStatus()), PlnIssueMonthEntity::getStatus, plnIssueMonth.getStatus())
                .orderByAsc(PlnIssueMonthEntity::getStatus).orderByDesc(PlnIssueMonthEntity::getCreateTime);
        if (ArrayUtil.isNotEmpty(plnIssueMonth.getIssueTime())) {
            wrapper.lambda().between(PlnIssueMonthEntity::getIssueTime, plnIssueMonth.getIssueTime()[0].atStartOfDay(), plnIssueMonth.getIssueTime()[1].atTime(LocalTime.MAX));
        }
        if (ArrayUtil.isNotEmpty(plnIssueMonth.getDemandTime())) {
            wrapper.lambda().between(PlnIssueMonthEntity::getDemandTime, plnIssueMonth.getDemandTime()[0], plnIssueMonth.getDemandTime()[1]);
        }
        return R.ok(plnIssueMonthService.page(page.toMpPage(), wrapper));
    }

    /**
     * 查询各采购排产状态的数量
     *
     * @return
     */

    @Operation(summary = "查询各采购排产状态的数量", description = "查询各采购排产状态的数量")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject PlnIssueDTO plnIssueMonth) {
        QueryWrapper<PlnIssueMonthEntity> wrapper = Wrappers.query();
        wrapper.lambda().select(PlnIssueMonthEntity::getStatus)
                .like(StrUtil.isNotBlank(plnIssueMonth.getNo()), PlnIssueMonthEntity::getIssueMonthNo, plnIssueMonth.getNo())
                .like(StrUtil.isNotBlank(plnIssueMonth.getCustomerCode()), PlnIssueMonthEntity::getCustomerCode, plnIssueMonth.getCustomerCode())
                .like(StrUtil.isNotBlank(plnIssueMonth.getCustomerName()), PlnIssueMonthEntity::getCustomerName, plnIssueMonth.getCustomerName())
                .like(StrUtil.isNotBlank(plnIssueMonth.getSpecificationModel()), PlnIssueMonthEntity::getSpecificationModel, plnIssueMonth.getSpecificationModel())
                .like(StrUtil.isNotBlank(plnIssueMonth.getProductCode()), PlnIssueMonthEntity::getProductCode, plnIssueMonth.getProductCode())
                .like(StrUtil.isNotBlank(plnIssueMonth.getProductName()), PlnIssueMonthEntity::getProductName, plnIssueMonth.getProductName());
        if (ArrayUtil.isNotEmpty(plnIssueMonth.getIssueTime())) {
            wrapper.lambda().between(PlnIssueMonthEntity::getIssueTime, plnIssueMonth.getIssueTime()[0].atStartOfDay(), plnIssueMonth.getIssueTime()[1].atTime(LocalTime.MAX));
        }
        if (ArrayUtil.isNotEmpty(plnIssueMonth.getDemandTime())) {
            wrapper.lambda().between(PlnIssueMonthEntity::getDemandTime, plnIssueMonth.getDemandTime()[0], plnIssueMonth.getDemandTime()[1]);
        }
        return R.ok(plnIssueMonthService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(PlnIssueMonthEntity::getStatus, Collectors.counting())));
    }

    /**
     * 通过id查询采购排产结果表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询采购排产结果表", description = "通过id查询采购排产结果表")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('plan_plnIssueMonth_view')")
    public R getById(@PathVariable("id") Long id) {
        return R.ok(plnIssueMonthService.getById(id));
    }

    /**
     * 新增或修改采购排产结果表
     *
     * @param plnIssueMonthVO 采购排产结果VO
     * @return R
     */
    @Operation(summary = "新增或修改采购排产结果表", description = "新增或修改采购排产结果表")
    @SysLog("新增或修改采购排产结果表")
    @PostMapping
    public R saveOrUpdateIssueMonth(@RequestBody PlnIssueMonthVO plnIssueMonthVO) {
        return R.ok(plnIssueMonthService.saveOrUpdateIssueMonth(plnIssueMonthVO));
    }

    /**
     * 下达月计划通知任务
     *
     * @param plnIssueMonthEntity 采购排产结果实体
     * @return R
     */
    @Operation(summary = "下达月计划通知任务", description = "下达月计划通知任务")
    @SysLog("下达月计划通知任务")
    @PostMapping("/save")
    public R save(@RequestBody PlnIssueMonthEntity plnIssueMonthEntity) {
        return R.ok(plnIssueMonthService.save(plnIssueMonthEntity));
    }

    /**
     * 修改采购排产结果表
     *
     * @param plnIssueMonth 采购排产结果表
     * @return R
     */
    @Operation(summary = "修改采购排产结果表", description = "修改采购排产结果表")
    @SysLog("修改采购排产结果表")
    @PutMapping
    public R updateById(@RequestBody PlnIssueMonthEntity plnIssueMonth) {
        return R.ok(plnIssueMonthService.updateIssueMonth(plnIssueMonth));
    }

    /**
     * 下发到各模块
     *
     * @param ids 计划下达ids
     * @return R
     */
    @Operation(summary = "下发到各模块", description = "下发到各模块")
    @SysLog("下发到各模块")
    @PostMapping("/module")
    public R module(@RequestBody Long[] ids) {
        return R.ok(plnIssueMonthService.module(ids));
    }

    /**
     * 通过id删除采购排产结果表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除采购排产结果表", description = "通过id删除采购排产结果表")
    @SysLog("通过id删除采购排产结果表")
    @DeleteMapping
    public R removeById(@RequestBody Long[] ids) {
        return R.ok(plnIssueMonthService.removeBatchByIds(CollUtil.toList(ids)));
    }

    /**
     * 导出excel 表格
     *
     * @param plnIssueMonth 查询条件
     * @param ids           导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    public List<PlnIssueMonthEntity> export(PlnIssueMonthEntity plnIssueMonth, Long[] ids) {
        return plnIssueMonthService.list(Wrappers.lambdaQuery(plnIssueMonth).in(ArrayUtil.isNotEmpty(ids), PlnIssueMonthEntity::getId, ids));
    }

    /**
     * 按月统计计划下达数据
     *
     * @param monthPlanQueryDTO 查询条件
     */
    @Operation(summary = "按月统计计划下达数据", description = "按月统计计划下达数据")
    @GetMapping("/issue/month")
    public R<Map<String, List<PlnIssueMonthVO>>> getIssueMonth(@ParameterObject MonthPlanQueryDTO monthPlanQueryDTO) {
        return R.ok(plnIssueMonthService.getIssueMonth(monthPlanQueryDTO));
    }

    /**
     * T+N 月计划
     *
     * @param templateQueryDTO 查询条件
     */
    @Operation(summary = "查询排产列表", description = "查询排产列表")
    @GetMapping("/temporary")
    public R<List<Map<Object, Object>>> temporary(@ParameterObject TemporaryPlanQueryDTO templateQueryDTO) {
        return R.ok(plnIssueMonthService.temporary(templateQueryDTO));
    }

    /**
     * 修改采购计划下达表
     *
     * @param plnIssueMonthEntityList 采购计划下达列表
     * @return R
     */
    @Operation(summary = "修改采购计划下达表", description = "修改采购计划下达表")
    @SysLog("修改采购计划下达表")
    @PutMapping("/edit")
    public R<Boolean> edit(@RequestBody List<PlnIssueMonthEntity> plnIssueMonthEntityList) {
        return R.ok(plnIssueMonthService.edit(plnIssueMonthEntityList));
    }

    /**
     * 根据采购计划下达号查询采购排产结果
     *
     * @param issueMonthNo 采购计划下达号
     * @return R
     */
    @Operation(summary = "根据采购计划下达号查询采购排产结果", description = "根据采购计划下达号查询采购排产结果")
    @SysLog("根据采购计划下达号查询采购排产结果")
    @GetMapping("/getIssueMonthByNo")
    public R<PlnIssueMonthEntity> getIssueMonthByNo(@RequestParam("issueMonthNo") String issueMonthNo, @RequestParam("productCode") String productCode) {
        return R.ok(plnIssueMonthService.getOne(new LambdaQueryWrapper<PlnIssueMonthEntity>().eq(PlnIssueMonthEntity::getIssueMonthNo, issueMonthNo)
                .eq(PlnIssueMonthEntity::getProductCode, productCode), false));
    }
}