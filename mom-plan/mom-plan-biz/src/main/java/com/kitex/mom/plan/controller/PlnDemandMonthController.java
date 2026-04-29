package com.kitex.mom.plan.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.RequestExcel;
import com.kitex.mom.plan.dto.MonthPlanQueryDTO;
import com.kitex.mom.plan.dto.PlnDemandMonthBalanceDTO;
import com.kitex.mom.plan.dto.PlnDemandMonthDispatchDTO;
import com.kitex.mom.plan.service.PlnDemandMonthService;
import com.kitex.mom.plan.vo.PlnDemandMonthDeviationVO;
import com.kitex.mom.plan.vo.PlnDemandMonthExcelVO;
import com.kitex.mom.plan.vo.PlnDemandMonthVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 计划月需求表
 *
 * @author kitex
 * @date 2025-02-10 09:08:44
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/plnDemandMonth")
@Tag(description = "plnDemandMonth", name = "月度计划需求表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PlnDemandMonthController {

    private final PlnDemandMonthService plnDemandMonthService;

    /**
     * 导入月度计划excel 表格
     *
     * @param excelVOList   物料月需求列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    @PostMapping("/import")
    public R importPlanDemandMonth(@RequestExcel(headRowNumber = 2) List<PlnDemandMonthExcelVO> excelVOList, BindingResult bindingResult) {
        return plnDemandMonthService.importPlanDemandMonth(excelVOList, bindingResult);
    }

    /**
     * 通过条件查询月度计划需求
     *
     * @param monthPlanQueryDTO 查询条件
     */
    @Operation(summary = "通过条件查询月度计划需求", description = "通过条件查询月度计划需求")
    @GetMapping("/demand")
    public R<List<PlnDemandMonthVO>> getDemand(@ParameterObject MonthPlanQueryDTO monthPlanQueryDTO) {
        return R.ok(plnDemandMonthService.getDemand(monthPlanQueryDTO));
    }

    /**
     * 执行月需求平衡
     */
    @Operation(summary = "执行月需求平衡", description = "按产品维度生成可执行需求量")
    @PostMapping("/balance")
    public R<Boolean> balance(@RequestBody PlnDemandMonthBalanceDTO balanceDTO) {
        return R.ok(plnDemandMonthService.balance(balanceDTO));
    }

    /**
     * 下推月需求到计划下达
     */
    @Operation(summary = "下推月需求到计划下达", description = "按日拆分可执行需求并写入pln_issue")
    @PostMapping("/dispatchToIssue")
    public R<Boolean> dispatchToIssue(@RequestBody PlnDemandMonthDispatchDTO dispatchDTO) {
        return R.ok(plnDemandMonthService.dispatchToIssue(dispatchDTO));
    }

    /**
     * 月需求偏差分析
     */
    @Operation(summary = "月需求偏差分析", description = "返回Forecast/Confirmed/Executed偏差数据")
    @GetMapping("/deviation")
    public R<List<PlnDemandMonthDeviationVO>> deviation(@ParameterObject MonthPlanQueryDTO monthPlanQueryDTO) {
        return R.ok(plnDemandMonthService.deviation(monthPlanQueryDTO));
    }
}