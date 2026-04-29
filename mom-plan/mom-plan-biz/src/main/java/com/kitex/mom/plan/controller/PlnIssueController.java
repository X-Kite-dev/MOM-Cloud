package com.kitex.mom.plan.controller;


import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.oms.dto.OmsOrderLifeCycleDTO;
import com.kitex.mom.plan.dto.MonthPlanQueryDTO;
import com.kitex.mom.plan.dto.PlnIssueDTO;
import com.kitex.mom.plan.dto.PlnIssueModuleDTO;
import com.kitex.mom.plan.dto.TemporaryPlanQueryDTO;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnIssueFeedbackEntity;
import com.kitex.mom.plan.service.PlnIssueService;
import com.kitex.mom.plan.vo.PlnIssueMonthVO;
import com.kitex.mom.plan.vo.PlnIssueVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 生产排产结果管理
 *
 * @author kitex
 * @date 2025-02-06 11:49:55
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/plnIssue")
@Tag(description = "plnIssue", name = "生产排产结果管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PlnIssueController {

    private final PlnIssueService plnIssueService;

    /**
     * 分页查询生产排产结果
     *
     * @param page     分页对象
     * @param plnIssue 生产排产结果表
     * @return
     */
    @Operation(summary = "分页查询生产排产结果", description = "分页查询生产排产结果")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('pln_plnIssue_view')")
    public R<Page<PlnIssueEntity>> getPlnIssuePage(@ParameterObject SearchPage page, @ParameterObject PlnIssueDTO plnIssue) {
        QueryWrapper<PlnIssueEntity> wrapper = Wrappers.query();
        wrapper.lambda()
                .like(StrUtil.isNotBlank(plnIssue.getNo()), PlnIssueEntity::getIssueNo, plnIssue.getNo())
                .like(StrUtil.isNotBlank(plnIssue.getCustomerCode()), PlnIssueEntity::getCustomerCode, plnIssue.getCustomerCode())
                .like(StrUtil.isNotBlank(plnIssue.getCustomerName()), PlnIssueEntity::getCustomerName, plnIssue.getCustomerName())
                .like(StrUtil.isNotBlank(plnIssue.getProductCode()), PlnIssueEntity::getProductCode, plnIssue.getProductCode())
                .like(StrUtil.isNotBlank(plnIssue.getProductName()), PlnIssueEntity::getProductName, plnIssue.getProductName())
                .like(StrUtil.isNotBlank(plnIssue.getSpecificationModel()), PlnIssueEntity::getSpecificationModel, plnIssue.getSpecificationModel())
                .eq(StrUtil.isNotBlank(plnIssue.getStatus()), PlnIssueEntity::getStatus, plnIssue.getStatus())
                .orderByAsc(PlnIssueEntity::getStatus).orderByDesc(PlnIssueEntity::getCreateTime);
        if (ArrayUtil.isNotEmpty(plnIssue.getIssueTime())) {
            wrapper.lambda().between(PlnIssueEntity::getIssueTime, plnIssue.getIssueTime()[0].atStartOfDay(), plnIssue.getIssueTime()[1].atTime(LocalTime.MAX));
        }
        if (ArrayUtil.isNotEmpty(plnIssue.getDemandTime())) {
            wrapper.lambda().between(PlnIssueEntity::getDemandTime, plnIssue.getDemandTime()[0], plnIssue.getDemandTime()[1]);
        }
        return R.ok(plnIssueService.page(page.toMpPage(), wrapper));
    }

    /**
     * 查询各生产排产状态的数量
     *
     * @return
     */

    @Operation(summary = "查询各生产排产状态的数量", description = "查询各生产排产状态的数量")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject PlnIssueDTO plnIssue) {
        QueryWrapper<PlnIssueEntity> wrapper = Wrappers.query();
        wrapper.lambda().select(PlnIssueEntity::getStatus)
                .like(StrUtil.isNotBlank(plnIssue.getNo()), PlnIssueEntity::getIssueNo, plnIssue.getNo())
                .like(StrUtil.isNotBlank(plnIssue.getCustomerCode()), PlnIssueEntity::getCustomerCode, plnIssue.getCustomerCode())
                .like(StrUtil.isNotBlank(plnIssue.getCustomerName()), PlnIssueEntity::getCustomerName, plnIssue.getCustomerName())
                .like(StrUtil.isNotBlank(plnIssue.getSpecificationModel()), PlnIssueEntity::getSpecificationModel, plnIssue.getSpecificationModel())
                .like(StrUtil.isNotBlank(plnIssue.getProductCode()), PlnIssueEntity::getProductCode, plnIssue.getProductCode())
                .like(StrUtil.isNotBlank(plnIssue.getProductName()), PlnIssueEntity::getProductName, plnIssue.getProductName());
        if (ArrayUtil.isNotEmpty(plnIssue.getIssueTime())) {
            wrapper.lambda().between(PlnIssueEntity::getIssueTime, plnIssue.getIssueTime()[0].atStartOfDay(), plnIssue.getIssueTime()[1].atTime(LocalTime.MAX));
        }
        if (ArrayUtil.isNotEmpty(plnIssue.getDemandTime())) {
            wrapper.lambda().between(PlnIssueEntity::getDemandTime, plnIssue.getDemandTime()[0], plnIssue.getDemandTime()[1]);
        }
        return R.ok(plnIssueService.list(wrapper)
                .stream()
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(PlnIssueEntity::getStatus, Collectors.counting())));
    }

    /**
     * 主生产计划分页查询
     *
     * @param page           分页对象
     * @param plnIssueEntity 查询对象
     * @return R
     */

    @Operation(summary = "主生产计划分页查询", description = "主生产计划分页查询")
    @GetMapping("/mesPlan/page")
    public R<Page<PlnIssueEntity>> getMesPlanPage(@ParameterObject SearchPage page,
                                                   @RequestParam(value = "issueTime", required = false) String issueTime,
                                                   @ParameterObject PlnIssueEntity plnIssueEntity) {
        if (StrUtil.isNotBlank(issueTime) && plnIssueEntity.getIssueTime() == null) {
            try {
                plnIssueEntity.setIssueTime(LocalDate.parse(issueTime).atStartOfDay());
            } catch (DateTimeParseException ignored) {
            }
        }
        return R.ok(plnIssueService.getMesPlanPage(page.toMpPage(), plnIssueEntity));
    }

    /**
     * 主生产计划日期
     *
     * @param productCodes 产品编码列表
     * @return R
     */

    @Operation(summary = "主生产计划日期", description = "主生产计划日期")
    @GetMapping("/mesPlan/details")
    public R<List<Map<Object, Object>>> getMesPlanDetails(@ParameterObject String[] productCodes, @ParameterObject Long factoryCode) {
        return R.ok(plnIssueService.getMesPlanDetails(productCodes));
    }

    /**
     * 根据产品编码和下达日期查询列表
     *
     * @param productCode 产品编码
     * @param issueDate   下达时间
     * @param status      状态
     * @return R
     */
    @Operation(summary = "根据产品编码和下达日期查询列表", description = "根据产品编码和下达日期查询列表")
    @GetMapping("/list")
    public R<List<PlnIssueEntity>> getIssueListByDate(@RequestParam("productCode") String productCode,
                                                      @RequestParam("issueTime") LocalDate issueDate,
                                                      @RequestParam("status") String status) {
        return R.ok(plnIssueService.getIssueListByDate(productCode, issueDate, status));
    }

    /**
     * 通过id查询生产排产结果表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询生产排产结果表", description = "通过id查询生产排产结果表")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('pln_plnIssue_view')")
    public R<PlnIssueEntity> getIssueDetail(@PathVariable("id") Long id) {
        return R.ok(plnIssueService.getIssueDetail(id));
    }

    /**
     * 新增或修改生产排产结果任务
     *
     * @param plnIssueVO 生产排产结果VO
     * @return R
     */
    @Operation(summary = "新增或修改生产排产结果任务", description = "新增或修改生产排产结果任务")
    @SysLog("新增或修改生产排产结果任务")
    @PostMapping
    public R<Boolean> save(@RequestBody PlnIssueVO plnIssueVO) {
        return R.ok(plnIssueService.saveOrUpdatePlnIssue(plnIssueVO));
    }

    /**
     * 新增生产排产结果表
     *
     * @param plnIssue 生产排产结果表
     * @return R
     */
    @Operation(summary = "新增生产排产结果表", description = "新增生产排产结果表")
    @SysLog("新增生产排产结果表")
    @PostMapping("/save")
    public R<Boolean> saveIssue(@RequestBody PlnIssueEntity plnIssue) {
        return R.ok(plnIssueService.save(plnIssue));
    }

    /**
     * 修改生产排产结果表
     *
     * @param plnIssue 生产排产结果表
     * @return R
     */
    @Operation(summary = "修改生产排产结果表", description = "修改生产排产结果表")
    @SysLog("修改生产排产结果表")
    @PutMapping
    public R<Boolean> updateById(@RequestBody PlnIssueEntity plnIssue) {
        return R.ok(plnIssueService.updateIssue(plnIssue));
    }

    /**
     * 通过id删除生产排产结果表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除生产排产结果表", description = "通过id删除生产排产结果表")
    @SysLog("通过id删除生产排产结果表")
    @DeleteMapping
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(plnIssueService.removeBatchIssue(CollUtil.toList(ids)));
    }

    /**
     * 导出excel 表格
     *
     * @param plnIssue 查询条件
     * @param ids      导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    public List<PlnIssueEntity> export(PlnIssueEntity plnIssue, Long[] ids) {
        return plnIssueService.list(Wrappers.lambdaQuery(plnIssue).in(ArrayUtil.isNotEmpty(ids), PlnIssueEntity::getId, ids));
    }

    /**
     * 通过条件查询生产排产结果
     *
     * @param templateQueryDTO 查询条件
     */
    @Operation(summary = "通过条件查询生产排产结果", description = "通过条件查询生产排产结果")
    @GetMapping("/issue/date")
    public R<Map<String, List<PlnIssueVO>>> getIssueDate(@ParameterObject TemporaryPlanQueryDTO templateQueryDTO) {
        return R.ok(plnIssueService.getIssueDate(templateQueryDTO));
    }

    /**
     * 下发到各模块
     *
     * @param plnIssueModuleDTO 生产计划下发DTO
     * @return R
     */
    @Operation(summary = "下发到各模块", description = "下发到各模块")
    @SysLog("下发到各模块")
    @PostMapping("/module")
    public R<Boolean> module(@RequestBody PlnIssueModuleDTO plnIssueModuleDTO) {
        return R.ok(plnIssueService.module(plnIssueModuleDTO));
    }

    /**
     * 生产排产结果反馈
     *
     * @param plnFeedbackEntities 生产排产结果反馈实体列表
     * @return R
     */
    @Operation(summary = "生产排产结果反馈", description = "生产排产结果反馈")
    @SysLog("生产排产结果反馈")
    @PostMapping("/feedback")
    public R<Boolean> feedback(@RequestBody List<PlnIssueFeedbackEntity> plnFeedbackEntities) {
        return R.ok(plnIssueService.feedback(plnFeedbackEntities));
    }

    /**
     * 通过条件查询上个月生产生产排产结果
     *
     * @param monthPlanQueryDTO 查询条件
     */
    @Operation(summary = "通过条件查询上个月生产生产排产结果", description = "通过条件查询上个月生产生产排产结果")
    @GetMapping("/issue/upMonth")
    public R<List<Map<String, Object>>> getIssueUpMonth(@ParameterObject MonthPlanQueryDTO monthPlanQueryDTO) {
        return R.ok(plnIssueService.getIssueUpMonth(monthPlanQueryDTO));
    }

    /**
     * 查询生产排产列表
     *
     * @param temporaryPlanQueryDTO 查询条件
     */
    @Operation(summary = "查询生产排产列表", description = "查询生产排产列表")
    @GetMapping("/mesPlan")
    public R<List<Map<Object, Object>>> getMesPlan(@ParameterObject TemporaryPlanQueryDTO temporaryPlanQueryDTO) {
        return R.ok(plnIssueService.getMesPlan(temporaryPlanQueryDTO));
    }

    /**
     * 生产计划排产
     *
     * @param plnIssueMonthVO 月生产排产结果VO
     * @return R
     */
    @Operation(summary = "生产计划排产", description = "生产计划排产")
    @SysLog("生产计划排产")
    @PostMapping("/mes/save")
    public R<Boolean> mesSaveOrUpdate(@RequestBody PlnIssueMonthVO plnIssueMonthVO) {
        return R.ok(plnIssueService.mesSaveOrUpdate(plnIssueMonthVO));
    }

    /**
     * 修改生产生产排产结果表
     *
     * @param omsOrderLifeCycleDtoList 订单生命周期DTO
     * @return R
     */
    @Operation(summary = "修改生产生产排产结果表", description = "修改生产生产排产结果表")
    @SysLog("修改生产生产排产结果表")
    @PutMapping("/edit")
    public R<Boolean> edit(@RequestBody List<OmsOrderLifeCycleDTO> omsOrderLifeCycleDtoList) {
        return R.ok(plnIssueService.edit(omsOrderLifeCycleDtoList));
    }

    /**
     * 根据生产生产排产结果号查询生产排产结果
     *
     * @param issueNo 生产生产排产结果号
     * @return R
     */
    @Operation(summary = "根据生产生产排产结果号查询生产排产结果", description = "根据生产生产排产结果号查询生产排产结果")
    @SysLog("根据生产生产排产结果号查询生产排产结果")
    @GetMapping("/getIssueByNo")
    public R<PlnIssueEntity> getIssueByNo(@RequestParam("issueNo") String issueNo, @RequestParam("productCode") String productCode) {
        return R.ok(plnIssueService.getOne(new LambdaQueryWrapper<PlnIssueEntity>().eq(PlnIssueEntity::getIssueNo, issueNo)
                .eq(PlnIssueEntity::getProductCode, productCode), false));
    }
}