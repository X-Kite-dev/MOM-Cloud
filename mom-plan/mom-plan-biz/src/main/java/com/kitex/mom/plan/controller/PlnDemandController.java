package com.kitex.mom.plan.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.RequestExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.plan.dto.TemporaryPlanQueryDTO;
import com.kitex.mom.plan.entity.PlnDemandEntity;
import com.kitex.mom.plan.service.PlnDemandService;
import com.kitex.mom.plan.vo.PlanDemandExcelVO;
import com.kitex.mom.plan.vo.PlnDemandVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 计划需求表
 *
 * @author kitex
 * @date 2025-02-05 16:46:53
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/plnDemand")
@Tag(description = "plnDemand", name = "T+N计划需求表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PlnDemandController {

    private final PlnDemandService plnDemandService;

    /**
     * 导入T+N计划排产excel 表格
     *
     * @param excelVOList   物料需求列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    @PostMapping("/import")
    public R importPlanDemand(@RequestExcel(headRowNumber = 2) List<PlanDemandExcelVO> excelVOList, BindingResult bindingResult) {
        return plnDemandService.importPlanDemand(excelVOList, bindingResult);
    }

    /**
     * 通过条件查询T+N需求
     *
     * @param templateQueryDTO 查询条件
     */
    @Operation(summary = "通过条件查询T+N需求", description = "通过条件查询T+N需求")
    @GetMapping("/demand")
    public R<List<PlnDemandVO>> getDemand(@ParameterObject TemporaryPlanQueryDTO templateQueryDTO) {
        return R.ok(plnDemandService.getDemand(templateQueryDTO));
    }

    /**
     * 查询T+N页面生产情况
     *
     * @param templateQueryDTO 查询条件
     */
    @Operation(summary = "查询T+N页面生产情况", description = "查询T+N页面生产情况")
    @GetMapping("/planMes")
    public R<List<Map<String, String>>> getPlanMes(@ParameterObject TemporaryPlanQueryDTO templateQueryDTO) {
        return R.ok(plnDemandService.getPlanMes(templateQueryDTO));
    }

    /**
     * 修改T+N需求表
     *
     * @param plnDemand 计划需求表
     * @return R
     */
    @Operation(summary = "修改T+N需求表", description = "修改T+N需求表")
    @SysLog("修改T+N需求表")
    @PutMapping
    public R updateById(@RequestBody PlnDemandEntity plnDemand) {
        return R.ok(plnDemandService.updateById(plnDemand));
    }
}