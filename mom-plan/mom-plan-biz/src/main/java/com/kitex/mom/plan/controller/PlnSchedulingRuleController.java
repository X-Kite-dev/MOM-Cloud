package com.kitex.mom.plan.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.plan.dto.*;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.service.PlnDemandMonthService;
import com.kitex.mom.plan.service.PlnSchedulingEngineService;
import com.kitex.mom.plan.service.PlnSchedulingRuleService;
import com.kitex.mom.plan.service.SchedulingContext;
import com.kitex.mom.plan.vo.PlnDemandMonthReadinessVO;
import com.kitex.mom.plan.vo.PlnSchedulingGanttDetailVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 排产规则中心
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/plnScheduling")
@Tag(description = "plnScheduling", name = "排产规则中心")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PlnSchedulingRuleController {

    private final PlnSchedulingRuleService plnSchedulingRuleService;
    private final PlnSchedulingEngineService plnSchedulingEngineService;
    private final PlnDemandMonthService plnDemandMonthService;

    /**
     * 新增规则集
     */
    @Operation(summary = "新增规则集", description = "新增规则集")
    @PostMapping("/rules")
//    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_add')")
    public R<Boolean> createRule(@RequestBody PlnSchedulingRuleSetCreateDTO ruleSetCreateDTO) {
        if (ruleSetCreateDTO == null || ruleSetCreateDTO.getRuleCode() == null || ruleSetCreateDTO.getRuleCode().trim().isEmpty()) {
            throw new CheckedException("规则编码不能为空");
        }
        if (ruleSetCreateDTO.getRuleName() == null || ruleSetCreateDTO.getRuleName().trim().isEmpty()) {
            throw new CheckedException("规则名称不能为空");
        }

        PlnSchedulingRuleSetEntity ruleSet = new PlnSchedulingRuleSetEntity();
        BeanUtils.copyProperties(ruleSetCreateDTO, ruleSet);
        return R.ok(plnSchedulingRuleService.createRule(ruleSet));
    }

    /**
     * 分页查询排产任务
     *
     * @param page 分页参数
     * @return R
     */
    @Operation(summary = "分页查询排产任务", description = "分页查询排产任务")
    @GetMapping("/tasks/page")
//    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_view')")
    public R<Page<PlnSchedulingTaskEntity>> pageTask(@ParameterObject SearchPage page,
                                                     @RequestParam(required = false) String mode) {
        return R.ok(plnSchedulingRuleService.pageTask(page, mode));
    }

    /**
     * 分页查询规则集
     */
    @Operation(summary = "分页查询规则集", description = "分页查询规则集")
    @GetMapping("/rules/page")
//    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_view')")
    public R<Page<PlnSchedulingRuleSetEntity>> pageRule(@ParameterObject SearchPage page,
                                                        @RequestParam(required = false) String ruleCode,
                                                        @RequestParam(required = false) String ruleName,
                                                        @RequestParam(required = false) Boolean active,
                                                        @RequestParam(required = false) Boolean defaultRule) {
        PlnSchedulingRuleSetEntity query = new PlnSchedulingRuleSetEntity();
        if (StrUtil.isNotBlank(ruleCode)) {
            query.setRuleCode(ruleCode);
        }
        if (StrUtil.isNotBlank(ruleName)) {
            query.setRuleName(ruleName);
        }
        query.setActive(active);
        query.setDefaultRule(defaultRule);
        return R.ok(plnSchedulingRuleService.pageRule(page, query));
    }

    /**
     * 切换规则启停
     */
    @Operation(summary = "切换规则启停", description = "切换规则启停")
    @PostMapping("/rules/{id}/toggle")
//    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_edit')")
    public R<Boolean> toggleRule(@PathVariable Long id,
                                 @RequestParam Boolean active,
                                 @RequestParam(required = false) Boolean defaultRule) {
        return R.ok(plnSchedulingRuleService.toggleRule(id, active, defaultRule));
    }

    /**
     * 编辑规则集
     */
    @Operation(summary = "编辑规则集", description = "编辑规则集")
    @PutMapping("/rules/{id}")
//    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_edit')")
    public R<Boolean> updateRule(@PathVariable Long id, @RequestBody PlnSchedulingRuleSetCreateDTO ruleSetCreateDTO) {
        if (ruleSetCreateDTO == null || ruleSetCreateDTO.getRuleCode() == null || ruleSetCreateDTO.getRuleCode().trim().isEmpty()) {
            throw new CheckedException("规则编码不能为空");
        }
        if (ruleSetCreateDTO.getRuleName() == null || ruleSetCreateDTO.getRuleName().trim().isEmpty()) {
            throw new CheckedException("规则名称不能为空");
        }

        PlnSchedulingRuleSetEntity ruleSet = new PlnSchedulingRuleSetEntity();
        BeanUtils.copyProperties(ruleSetCreateDTO, ruleSet);
        return R.ok(plnSchedulingRuleService.updateRule(id, ruleSet));
    }

    /**
     * 删除规则集
     */
    @Operation(summary = "删除规则集", description = "删除规则集")
    @DeleteMapping("/rules/{id}")
//    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_del')")
    public R<Boolean> removeRule(@PathVariable Long id) {
        return R.ok(plnSchedulingRuleService.removeRule(id));
    }

    /**
     * 查询排产规则可用类型及 ruleContent JSON 模板
     * <p>
     * 返回所有支持的规则类型说明与完整的 ruleContent 配置示例，
     * 便于前端构建规则编辑界面。
     */
    @Operation(summary = "查询排产规则类型及配置模板", description = "返回 hardRules/softRules 支持的类型及 ruleContent 完整示例")
    @GetMapping("/rules/schema")
    public R<java.util.Map<String, Object>> ruleSchema() {
        java.util.Map<String, Object> schema = new java.util.LinkedHashMap<>();

        java.util.List<java.util.Map<String, String>> hardTypes = java.util.List.of(
                java.util.Map.of("type", "DEMAND_DATE_REQUIRED",
                        "desc", "需求日期必填——缺少 demandTime 的计划被过滤，不参与排产"),
                java.util.Map.of("type", "SCHEDULE_AFTER_DEMAND_FORBIDDEN",
                        "desc", "禁止超期排产——分配日期晚于需求日期时硬拦截")
        );

        java.util.List<java.util.Map<String, Object>> softTypes = java.util.List.of(
                java.util.Map.of("type", "EARLIER_DEMAND_FIRST",
                        "desc", "需求日期越早优先级越高",
                        "params", java.util.Map.of("weight", "权重，默认1")),
                java.util.Map.of("type", "SLACK_TIME",
                        "desc", "可用松弛时间越小优先级越高（截止日期越近越急迫，超期给最高分）",
                        "params", java.util.Map.of("weight", "权重，默认1", "urgencyDays", "紧急阈值天数，默认7")),
                java.util.Map.of("type", "LARGE_ORDER_FIRST",
                        "desc", "数量越大优先级越高，减少换产次数",
                        "params", java.util.Map.of("weight", "权重，默认1"))
        );

        String example = "{\n"
                + "  \"hardRules\": [\n"
                + "    { \"type\": \"DEMAND_DATE_REQUIRED\" },\n"
                + "    { \"type\": \"SCHEDULE_AFTER_DEMAND_FORBIDDEN\" }\n"
                + "  ],\n"
                + "  \"softRules\": [\n"
                + "    { \"type\": \"EARLIER_DEMAND_FIRST\", \"weight\": 10 },\n"
                + "    { \"type\": \"SLACK_TIME\", \"weight\": 5, \"urgencyDays\": 7 },\n"
                + "    { \"type\": \"LARGE_ORDER_FIRST\", \"weight\": 1 }\n"
                + "  ],\n"
                + "  \"dayCapacityThreshold\": 3\n"
                + "}";

        schema.put("hardRuleTypes", hardTypes);
        schema.put("softRuleTypes", softTypes);
        schema.put("dayCapacityThresholdDesc", "同产品同日最大排产数量阈值，超出则产出 PRODUCT_DAY_CAPACITY_EXCEEDED 软冲突警告（默认3）");
        schema.put("ruleContentExample", example);
        return R.ok(schema);
    }

    @Operation(summary = "查询已锁定计划甘特数据", description = "排产确认锁定后，按 scheduledDate 展示甘特")
    @GetMapping("/locked/gantt")
    public R<Page<PlnSchedulingGanttDetailVO>> lockedGantt(@ParameterObject SearchPage page,
                                                           @RequestParam(required = false) String productCode,
                                                           @RequestParam(required = false) String productName,
                                                           @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate startDate,
                                                           @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate endDate) {
        return R.ok(plnSchedulingRuleService.queryLockedGantt(page, productCode,productName, startDate, endDate));
    }

    @Operation(summary = "分页查询排产甘特明细", description = "分页查询排产甘特明细")
    @GetMapping("/gantt/details")
    public R<Page<PlnSchedulingGanttDetailVO>> queryGanttDetails(@ParameterObject SearchPage page,
                                                                 @RequestParam(required = false) Long taskId,
                                                                 @RequestParam(required = false) String taskCode,
                                                                 @RequestParam(required = false) String routeCode) {
        PlnSchedulingGanttDetailQueryDTO query = new PlnSchedulingGanttDetailQueryDTO();
        query.setTaskId(taskId);
        query.setTaskCode(taskCode);
        query.setRouteCode(routeCode);
        return R.ok(plnSchedulingRuleService.queryGanttDetails(page, query));
    }

    /**
     * 查询排产任务命中明细
     */
    @Operation(summary = "查询排产任务命中明细", description = "查询排产任务命中明细")
    @GetMapping("/tasks/{taskId}/details")
    public R<java.util.List<PlnIssueEntity>> taskDetails(@PathVariable Long taskId) {
        return R.ok(plnSchedulingRuleService.listTaskDetails(taskId));
    }

    @Operation(summary = "调整排产明细分配日期", description = "scheduledEndDate 不传时自动按产率+工作日历计算，传入时以手动值为准")
    @PutMapping("/tasks/{taskId}/details/scheduleDate")
    public R<java.util.Map<String, java.time.LocalDate>> adjustTaskDetailScheduleDate(@PathVariable Long taskId,
                                                                                      @RequestBody PlnSchedulingAdjustDateDTO dto) {
        return R.ok(plnSchedulingRuleService.adjustTaskDetailScheduleDate(taskId, dto.getIssueId(), dto.getScheduledDate(), dto.getScheduledEndDate()));
    }

    /**
     * 分页查询冲突面板
     *
     * @param page 分页参数
     * @return R
     */
    @Operation(summary = "分页查询冲突面板", description = "分页查询冲突面板")
    @GetMapping("/conflicts/page")
//    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_view')")
    public R<Page<PlnSchedulingConflictEntity>> pageConflict(@ParameterObject SearchPage page,
                                                             @RequestParam(required = false) Long taskId) {
        return R.ok(plnSchedulingRuleService.pageConflict(page, taskId));
    }

    /**
     * 执行排产（模拟/正式）
     *
     * @param runDTO 运行参数
     * @return R
     */
    @Operation(summary = "执行排产", description = "执行排产")
    @PostMapping("/run")
//    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_run')")
    public R<Boolean> run(@RequestBody PlnSchedulingRunDTO runDTO) {
        try {
            Boolean result = plnSchedulingEngineService.run(runDTO);
            String warning = SchedulingContext.getAndClearWarning();
            return warning != null ? R.ok(result, warning) : R.ok(result);
        } finally {
            SchedulingContext.clear();
        }
    }

    @Operation(summary = "确认正式排产锁定", description = "确认正式排产锁定")
    @PostMapping("/tasks/{taskId}/confirmLock")
    public R<Boolean> confirmLock(@PathVariable Long taskId) {
        return R.ok(plnSchedulingEngineService.confirmOfficialTaskLock(taskId));
    }

    @Operation(summary = "查询月需求准备状态", description = "返回月需求是否已平衡、是否已下推")
    @GetMapping("/monthDemand/readiness")
    public R<PlnDemandMonthReadinessVO> monthDemandReadiness(@ParameterObject com.kitex.mom.plan.dto.MonthPlanQueryDTO monthPlanQueryDTO) {
        return R.ok(plnDemandMonthService.readiness(monthPlanQueryDTO));
    }

    /**
     * 按状态分层重排
     *
     * @param replanDTO 重排参数
     * @return R
     */
    @Operation(summary = "按状态分层重排", description = "按状态分层重排")
    @PostMapping("/replan")
//    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_replan')")
    public R<Boolean> replan(@RequestBody PlnSchedulingReplanDTO replanDTO) {
        return R.ok(plnSchedulingEngineService.replan(replanDTO));
    }

    /**
     * 批量重新调整排产日期
     * @param list
     * @return R
     */
    @Operation(summary = "批量重新调整排产日期", description = "支持传入任意字段，只更新 id 对应的 planDate")
    @PutMapping("/savePlanDate")
    public R<Boolean> savePlanDateBatch(@RequestBody List<PlnSchedulingTaskDetailEntity> list) {
        boolean result = plnSchedulingEngineService.savePlanDateBatch(list);
        return R.ok(result);
    }
}
