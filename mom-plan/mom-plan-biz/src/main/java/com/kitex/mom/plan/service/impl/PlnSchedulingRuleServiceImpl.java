package com.kitex.mom.plan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.constant.CommonConstants;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmCalendarEntity;
import com.kitex.mom.mdm.entity.MdmClassesEntity;
import com.kitex.mom.mdm.entity.MdmProcessRouteEntity;
import com.kitex.mom.mdm.feign.RemoteCalendarService;
import com.kitex.mom.mdm.feign.RemoteClassesService;
import com.kitex.mom.mdm.feign.RemoteProcessRouteService;
import com.kitex.mom.plan.dto.PlnSchedulingGanttDetailQueryDTO;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.PlnIssueMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingConflictMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingRuleSetMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingTaskMapper;
import com.kitex.mom.plan.service.PlnSchedulingRuleService;
import com.kitex.mom.plan.vo.PlnSchedulingGanttDetailVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import cn.hutool.core.util.ObjUtil;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlnSchedulingRuleServiceImpl extends ServiceImpl<PlnSchedulingRuleSetMapper, PlnSchedulingRuleSetEntity>
        implements PlnSchedulingRuleService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int WORK_HOURS_PER_DAY = 8;

    private final PlnSchedulingTaskMapper plnSchedulingTaskMapper;
    private final PlnSchedulingConflictMapper plnSchedulingConflictMapper;
    private final PlnIssueMapper plnIssueMapper;
    private final com.kitex.mom.plan.mapper.PlnSchedulingTaskDetailMapper plnSchedulingTaskDetailMapper;
    private final RemoteProcessRouteService remoteProcessRouteService;
    private final RemoteClassesService remoteClassesService;
    private final RemoteCalendarService remoteCalendarService;

    @Override
    public Boolean createRule(PlnSchedulingRuleSetEntity ruleSet) {
        validateRuleContent(ruleSet.getRuleContent());
        return this.save(ruleSet);
    }

    @Override
    public Page<PlnSchedulingTaskEntity> pageTask(SearchPage page, String mode) {
        return plnSchedulingTaskMapper.selectPage(page.toMpPage(), Wrappers.<PlnSchedulingTaskEntity>lambdaQuery()
                .eq(mode != null && !mode.isBlank(), PlnSchedulingTaskEntity::getMode, mode)
                .orderByDesc(PlnSchedulingTaskEntity::getCreateTime)
                .orderByDesc(PlnSchedulingTaskEntity::getId));
    }

    @Override
    public Page<PlnSchedulingRuleSetEntity> pageRule(SearchPage page, PlnSchedulingRuleSetEntity query) {
        QueryWrapper<PlnSchedulingRuleSetEntity> wrapper = Wrappers.query();
        wrapper.lambda()
                .like(query.getRuleCode() != null && !query.getRuleCode().isBlank(), PlnSchedulingRuleSetEntity::getRuleCode, query.getRuleCode())
                .like(query.getRuleName() != null && !query.getRuleName().isBlank(), PlnSchedulingRuleSetEntity::getRuleName, query.getRuleName())
                .eq(query.getActive() != null, PlnSchedulingRuleSetEntity::getActive, query.getActive())
                .eq(query.getDefaultRule() != null, PlnSchedulingRuleSetEntity::getDefaultRule, query.getDefaultRule())
                .orderByDesc(PlnSchedulingRuleSetEntity::getUpdateTime);
        return this.page(page.toMpPage(), wrapper);
    }

    @Override
    public Boolean toggleRule(Long id, Boolean active, Boolean defaultRule) {
        PlnSchedulingRuleSetEntity entity = this.getById(id);
        if (entity == null) {
            throw new CheckedException("规则不存在");
        }
        if (active == null) {
            throw new CheckedException("启停状态不能为空");
        }

        entity.setActive(active);
        if (defaultRule != null) {
            entity.setDefaultRule(defaultRule);
        }

        if (Boolean.TRUE.equals(entity.getDefaultRule()) && Boolean.FALSE.equals(entity.getActive())) {
            throw new CheckedException("默认规则不能被禁用");
        }

        return this.updateById(entity);
    }

    @Override
    public Boolean updateRule(Long id, PlnSchedulingRuleSetEntity ruleSet) {
        PlnSchedulingRuleSetEntity entity = this.getById(id);
        if (entity == null) {
            throw new CheckedException("规则不存在");
        }

        entity.setRuleCode(ruleSet.getRuleCode());
        entity.setRuleName(ruleSet.getRuleName());
        entity.setRuleVersion(ruleSet.getRuleVersion());
        validateRuleContent(ruleSet.getRuleContent());
        entity.setRuleContent(ruleSet.getRuleContent());
        entity.setRemark(ruleSet.getRemark());
        if (ruleSet.getActive() != null) {
            entity.setActive(ruleSet.getActive());
        }
        if (ruleSet.getDefaultRule() != null) {
            entity.setDefaultRule(ruleSet.getDefaultRule());
        }

        if (Boolean.TRUE.equals(entity.getDefaultRule()) && Boolean.FALSE.equals(entity.getActive())) {
            throw new CheckedException("默认规则不能被禁用");
        }

        return this.updateById(entity);
    }

    @Override
    public Boolean removeRule(Long id) {
        PlnSchedulingRuleSetEntity entity = this.getById(id);
        if (entity == null) {
            throw new CheckedException("规则不存在");
        }
        if (Boolean.TRUE.equals(entity.getDefaultRule())) {
            throw new CheckedException("默认规则不允许删除");
        }
        return this.removeById(id);
    }

    @Override
    public Page<PlnSchedulingConflictEntity> pageConflict(SearchPage page, Long taskId) {
        return plnSchedulingConflictMapper.selectPage(page.toMpPage(), Wrappers.<PlnSchedulingConflictEntity>lambdaQuery()
                .eq(taskId != null, PlnSchedulingConflictEntity::getTaskId, taskId)
                .orderByDesc(PlnSchedulingConflictEntity::getCreateTime)
                .orderByDesc(PlnSchedulingConflictEntity::getId));
    }

    @Override
    public List<PlnIssueEntity> listTaskDetails(Long taskId) {
        PlnSchedulingTaskEntity task = plnSchedulingTaskMapper.selectById(taskId);
        if (task == null) {
            throw new CheckedException("任务不存在");
        }
        if (task.getScheduleDate() == null) {
            return Collections.emptyList();
        }

        List<com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity> details = plnSchedulingTaskDetailMapper.selectList(
                Wrappers.<com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity>lambdaQuery()
                        .eq(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getTaskId, taskId)
                        .orderByAsc(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getDemandTime)
                        .orderByAsc(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getIssueNo)
        );
        if (details == null || details.isEmpty()) {
            return Collections.emptyList();
        }
        return details.stream().map(item -> {
            PlnIssueEntity issue = new PlnIssueEntity();
            issue.setId(item.getIssueId());
            issue.setIssueNo(item.getIssueNo());
            issue.setCustomerCode(item.getCustomerCode());
            issue.setCustomerName(item.getCustomerName());
            issue.setProductCode(item.getProductCode());
            issue.setProductName(item.getProductName());
            issue.setSpecificationModel(item.getSpecificationModel());
            issue.setNum(item.getNum());
            issue.setIssueTime(item.getIssueTime());
            issue.setDemandTime(item.getDemandTime());
            issue.setScheduledDate(item.getScheduledDate());
            issue.setScheduledEndDate(item.getScheduledEndDate());
            issue.setLockStatus(item.getLockStatus());
            issue.setLockTaskId(item.getLockTaskId());
            issue.setLockTime(item.getLockTime());
            issue.setStatus(item.getStatus());
            issue.setType(item.getType());
            issue.setPlanType(item.getPlanType());
            issue.setMatchedRules(item.getMatchedRules());
            issue.setScoreBreakdown(item.getScoreBreakdown());
            issue.setFactoryCode(item.getFactoryCode());
            return issue;
        }).toList();
    }

    @Override
    public Page<PlnSchedulingGanttDetailVO> queryGanttDetails(SearchPage page, PlnSchedulingGanttDetailQueryDTO query) {
        SearchPage effectivePage = page;
        if (effectivePage == null) {
            effectivePage = new SearchPage();
            effectivePage.setCurrent(1L);
            effectivePage.setSize(10L);
        }

        Page<PlnSchedulingGanttDetailVO> emptyPage = new Page<>(effectivePage.getCurrent(), effectivePage.getSize());
        emptyPage.setRecords(Collections.emptyList());
        emptyPage.setTotal(0L);

        if (query == null || (query.getTaskId() == null && (query.getTaskCode() == null || query.getTaskCode().isBlank()))) {
            return emptyPage;
        }

        List<PlnSchedulingTaskDetailEntity> details;
        if (query.getTaskId() != null) {
            Page<PlnSchedulingTaskDetailEntity> detailPage = plnSchedulingTaskDetailMapper.selectPage(
                    effectivePage.toMpPage(),
                    Wrappers.<PlnSchedulingTaskDetailEntity>lambdaQuery()
                            .eq(PlnSchedulingTaskDetailEntity::getTaskId, query.getTaskId())
                            .orderByAsc(PlnSchedulingTaskDetailEntity::getDemandTime)
                            .orderByAsc(PlnSchedulingTaskDetailEntity::getIssueNo)
                            .orderByAsc(PlnSchedulingTaskDetailEntity::getId)
            );
            details = detailPage.getRecords();
            Page<PlnSchedulingGanttDetailVO> result = new Page<>(detailPage.getCurrent(), detailPage.getSize(), detailPage.getTotal());
            result.setRecords(mapGanttDetails(details, query.getTaskCode(), query.getRouteCode()));
            return result;
        }

        // taskCode-only 查询分支：当前明细快照尚未持久化 taskCode/routeCode，暂返回空页以避免误查非目标任务数据。
        return emptyPage;
    }

    private List<PlnSchedulingGanttDetailVO> mapGanttDetails(List<PlnSchedulingTaskDetailEntity> details, String taskCode, String routeCode) {
        if (details == null || details.isEmpty()) {
            return Collections.emptyList();
        }
        return details.stream().map(item -> {
            PlnSchedulingGanttDetailVO vo = new PlnSchedulingGanttDetailVO();
            vo.setIssueId(item.getIssueId());
            vo.setIssueNo(item.getIssueNo());
            vo.setTaskCode(taskCode);
            vo.setRouteCode(routeCode); // 兼容快照字段未落地时，使用查询入参回填 taskCode/routeCode
            vo.setCustomerCode(item.getCustomerCode());
            vo.setCustomerName(item.getCustomerName());
            vo.setProductCode(item.getProductCode());
            vo.setProductName(item.getProductName());
            vo.setSpecificationModel(item.getSpecificationModel());
            vo.setNum(item.getNum());
            vo.setDemandTime(item.getDemandTime());
            vo.setScheduledDate(item.getScheduledDate());
            vo.setScheduledEndDate(item.getScheduledEndDate());
            vo.setIssueTime(item.getIssueTime());
            vo.setStatus(item.getStatus());
            vo.setLockStatus(item.getLockStatus());
            vo.setPlanType(item.getPlanType());
            return vo;
        }).collect(Collectors.toList());
    }

    private LocalDate getCycleStartDate(String cycleType, LocalDate baseDate) {
        if ("WEEK".equalsIgnoreCase(cycleType)) {
            return baseDate.minusDays(baseDate.getDayOfWeek().getValue() - 1L);
        }
        if ("MONTH".equalsIgnoreCase(cycleType)) {
            return baseDate.withDayOfMonth(1);
        }
        return baseDate;
    }

    private LocalDate getCycleEndDate(String cycleType, LocalDate baseDate, Integer planNum) {
        if ("WEEK".equalsIgnoreCase(cycleType)) {
            return baseDate.plusDays(7L - baseDate.getDayOfWeek().getValue());
        }
        if ("MONTH".equalsIgnoreCase(cycleType)) {
            return baseDate.withDayOfMonth(baseDate.lengthOfMonth());
        }
        if ("TN".equalsIgnoreCase(cycleType) || "T+N".equalsIgnoreCase(cycleType)) {
            int days = planNum == null ? 7 : Math.max(1, planNum);
            return baseDate.plusDays(days - 1L);
        }
        return baseDate;
    }

    @Override
    public Map<String, LocalDate> adjustTaskDetailScheduleDate(Long taskId, Long issueId, LocalDate scheduledDate, LocalDate scheduledEndDate) {
        if (isNull(taskId) || isNull(issueId) || isNull(scheduledDate)) {
            throw new CheckedException("调整参数不能为空");
        }
        PlnSchedulingTaskEntity task = plnSchedulingTaskMapper.selectById(taskId);
        if (task == null) {
            throw new CheckedException("任务不存在");
        }
        PlnIssueEntity issue = plnIssueMapper.selectById(issueId);
        if (issue == null) {
            throw new CheckedException("计划不存在");
        }
        if (!"official".equalsIgnoreCase(task.getMode())) {
            throw new CheckedException("仅正式排产任务允许调整分配日期");
        }
        Long detailCount = plnSchedulingTaskDetailMapper.selectCount(
                Wrappers.<com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity>lambdaQuery()
                        .eq(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getTaskId, taskId)
                        .and(q -> q.eq(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getIssueId, issueId)
                                .or()
                                .eq(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getIssueNo, issue.getIssueNo()))
        );
        boolean belongsByDetail = detailCount != null && detailCount > 0;
        boolean belongsByLock = taskId.equals(issue.getLockTaskId());
        if (!belongsByDetail && !belongsByLock) {
            throw new CheckedException("该计划不属于当前排产任务");
        }
        if (issue.getDemandTime() != null && scheduledDate.isAfter(issue.getDemandTime())) {
            throw new CheckedException("分配日期晚于需求日期，禁止确认");
        }
        // 手动传入结束日期时校验合法性
        if (scheduledEndDate != null && scheduledEndDate.isBefore(scheduledDate)) {
            throw new CheckedException("结束日期不能早于开始日期");
        }

        Long sameDayCount;
        if (belongsByLock) {
            sameDayCount = plnIssueMapper.selectCount(new LambdaQueryWrapper<PlnIssueEntity>()
                    .eq(PlnIssueEntity::getLockTaskId, taskId)
                    .eq(PlnIssueEntity::getProductCode, issue.getProductCode())
                    .eq(PlnIssueEntity::getScheduledDate, scheduledDate)
                    .eq(PlnIssueEntity::getLockStatus, true)
                    .ne(PlnIssueEntity::getId, issueId));
        } else {
            sameDayCount = plnSchedulingTaskDetailMapper.selectCount(
                    Wrappers.<com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity>lambdaQuery()
                            .eq(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getTaskId, taskId)
                            .eq(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getProductCode, issue.getProductCode())
                            .eq(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getScheduledDate, scheduledDate)
                            .and(q -> q.ne(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getIssueId, issueId)
                                    .or()
                                    .ne(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getIssueNo, issue.getIssueNo()))
            );
        }
        if (sameDayCount != null && sameDayCount >= resolveDayCapacityThreshold(task.getRuleCode())) {
            throw new CheckedException("同产品同日已达到产能阈值，禁止调整");
        }

        // 结束日期：手动传入优先，否则自动计算
        LocalDate finalEndDate = scheduledEndDate != null
                ? scheduledEndDate
                : calcScheduledEndDate(issue.getProductCode(), issue.getNum(), scheduledDate);

        issue.setScheduledDate(scheduledDate);
        boolean updated = plnIssueMapper.updateById(issue) > 0;
        if (updated) {
            plnSchedulingTaskDetailMapper.update(null, Wrappers.<com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity>lambdaUpdate()
                    .eq(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getTaskId, taskId)
                    .eq(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getIssueId, issueId)
                    .set(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getScheduledDate, scheduledDate)
                    .set(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getScheduledEndDate, finalEndDate));
        }
        Map<String, LocalDate> result = new LinkedHashMap<>();
        result.put("scheduledDate", scheduledDate);
        result.put("scheduledEndDate", finalEndDate);
        return result;
    }

    // ===================== 排产结束日期计算（内联，避免循环依赖） =====================

    /**
     * 根据产品产率 + 班次工时 + 工作日历，计算从 startDate 起所需的完工日期。
     * 若产品无工艺路线或产率为 0，则降级返回 startDate。
     */
    private LocalDate calcScheduledEndDate(String productCode, BigDecimal num, LocalDate startDate) {
        if (productCode == null || num == null || startDate == null) {
            return startDate;
        }
        try {
            MdmProcessRouteEntity route = queryDefaultRoute(productCode);
            if (route == null || route.getProductRate() == null
                    || route.getProductRate().compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("[calcScheduledEndDate] 产品 {} 无默认工艺路线或产率为0，降级返回开始日期", productCode);
                return startDate;
            }
            BigDecimal productRate = route.getProductRate();
            // 需要的生产小时数（向上取整）
            long neededHours = num.divide(productRate, 0, RoundingMode.CEILING).longValue();
            if (neededHours <= 0) {
                return startDate;
            }

            List<MdmClassesEntity> shifts = queryActiveShifts();
            long dailyCapacityHours = calcDailyCapacityHours(shifts);
            if (dailyCapacityHours <= 0) {
                dailyCapacityHours = WORK_HOURS_PER_DAY;
            }

            // 按工作日历推算完工日
            long remainHours = neededHours;
            LocalDate cursor = startDate;
            List<LocalDate> workDays = buildWorkDaySequence(startDate,
                    startDate.plusDays(neededHours / dailyCapacityHours + 30), // 留余量
                    (int) (neededHours / dailyCapacityHours + 1));
            for (LocalDate day : workDays) {
                remainHours -= dailyCapacityHours;
                cursor = day;
                if (remainHours <= 0) {
                    break;
                }
            }
            return cursor;
        } catch (Exception e) {
            log.warn("[calcScheduledEndDate] 计算完工日期异常，降级返回开始日期: {}", e.getMessage());
            return startDate;
        }
    }

    private MdmProcessRouteEntity queryDefaultRoute(String productCode) {
        try {
            R<MdmProcessRouteEntity> resp = remoteProcessRouteService.getDefaultByProductCode(productCode);
            if (resp != null && CommonConstants.SUCCESS.equals(resp.getCode())) {
                return resp.getData();
            }
        } catch (Exception e) {
            log.warn("[queryDefaultRoute] 查询工艺路线失败: {}", e.getMessage());
        }
        return null;
    }

    private List<MdmClassesEntity> queryActiveShifts() {
        try {
            R<List<MdmClassesEntity>> resp = remoteClassesService.listActive();
            if (resp != null && CommonConstants.SUCCESS.equals(resp.getCode())
                    && resp.getData() != null) {
                return resp.getData();
            }
        } catch (Exception e) {
            log.warn("[queryActiveShifts] 查询班次失败: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * 计算所有班次的每日总工时（小时）。
     * 每班次时长 = endTime - startTime（支持跨午夜班次）。
     */
    private long calcDailyCapacityHours(List<MdmClassesEntity> shifts) {
        if (shifts == null || shifts.isEmpty()) {
            return WORK_HOURS_PER_DAY;
        }
        long totalMinutes = 0;
        for (MdmClassesEntity shift : shifts) {
            if (shift.getStartTime() == null || shift.getEndTime() == null) {
                continue;
            }
            long minutes = java.time.Duration.between(shift.getStartTime(), shift.getEndTime()).toMinutes();
            // 跨午夜班次（end < start），加一天
            if (minutes < 0) {
                minutes += 24 * 60;
            }
            totalMinutes += minutes;
        }
        return totalMinutes > 0 ? totalMinutes / 60 : WORK_HOURS_PER_DAY;
    }

    /**
     * 构建工作日序列（含 startDate 在内），至少 minCount 个工作日，
     * 查询范围不足时自动扩展到 endDate 之后 60 天。
     */
    private List<LocalDate> buildWorkDaySequence(LocalDate startDate, LocalDate endDate, int minCount) {
        LocalDate queryEnd = endDate.isAfter(startDate.plusDays(60)) ? endDate : startDate.plusDays(60);
        Map<LocalDate, Boolean> workDayMap = fetchWorkDayMap(startDate, queryEnd);

        List<LocalDate> result = new ArrayList<>();
        LocalDate cursor = startDate;
        while (result.size() < Math.max(minCount, 1) || cursor.isBefore(endDate)) {
            if (isWorkDay(cursor, workDayMap)) {
                result.add(cursor);
            }
            cursor = cursor.plusDays(1);
            // 防止无限循环
            if (cursor.isAfter(startDate.plusDays(365))) {
                break;
            }
        }
        return result;
    }

    private boolean isWorkDay(LocalDate date, Map<LocalDate, Boolean> workDayMap) {
        Boolean flag = workDayMap.get(date);
        if (flag != null) {
            return flag; // 日历明确设置
        }
        // 自然周降级：周一~周五为工作日
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }

    private Map<LocalDate, Boolean> fetchWorkDayMap(LocalDate start, LocalDate end) {
        try {
            R<List<MdmCalendarEntity>> resp = remoteCalendarService.listByRange(start, end);
            if (resp != null && CommonConstants.SUCCESS.equals(resp.getCode())
                    && resp.getData() != null) {
                Map<LocalDate, Boolean> map = new HashMap<>();
                for (MdmCalendarEntity cal : resp.getData()) {
                    if (cal.getTime() != null) {
                        // status: "0"=上班, "1"=休息
                        map.put(cal.getTime(), "0".equals(cal.getStatus()));
                    }
                }
                return map;
            }
        } catch (Exception e) {
            log.warn("[fetchWorkDayMap] 查询工作日历失败，降级自然周: {}", e.getMessage());
        }
        return Collections.emptyMap();
    }

    // ==================================================================================

    private void validateRuleContent(String ruleContent) {
        if (ruleContent == null || ruleContent.isBlank()) {
            return;
        }
        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(ruleContent);
        } catch (JsonProcessingException e) {
            throw new CheckedException("规则配置必须为合法JSON");
        }
        // 校验 hardRules 类型合法性
        com.fasterxml.jackson.databind.JsonNode hardRules = root.get("hardRules");
        if (hardRules != null && hardRules.isArray()) {
            java.util.Set<String> validHard = java.util.Set.of("DEMAND_DATE_REQUIRED", "SCHEDULE_AFTER_DEMAND_FORBIDDEN");
            for (com.fasterxml.jackson.databind.JsonNode rule : hardRules) {
                String type = rule.has("type") ? rule.get("type").asText() : null;
                if (type == null || type.isBlank()) {
                    throw new CheckedException("hardRules 中存在缺少 type 字段的规则");
                }
                if (!validHard.contains(type)) {
                    throw new CheckedException("不支持的硬规则类型: " + type
                            + "，支持: " + validHard);
                }
            }
        }
        // 校验 softRules 类型合法性
        com.fasterxml.jackson.databind.JsonNode softRules = root.get("softRules");
        if (softRules != null && softRules.isArray()) {
            java.util.Set<String> validSoft = java.util.Set.of(
                    "EARLIER_DEMAND_FIRST", "SLACK_TIME", "LARGE_ORDER_FIRST");
            for (com.fasterxml.jackson.databind.JsonNode rule : softRules) {
                String type = rule.has("type") ? rule.get("type").asText() : null;
                if (type == null || type.isBlank()) {
                    throw new CheckedException("softRules 中存在缺少 type 字段的规则");
                }
                if (!validSoft.contains(type)) {
                    throw new CheckedException("不支持的软规则类型: " + type
                            + "，支持: " + validSoft);
                }
            }
        }
        // 校验 dayCapacityThreshold
        com.fasterxml.jackson.databind.JsonNode threshold = root.get("dayCapacityThreshold");
        if (threshold != null && (!threshold.isInt() || threshold.asInt() <= 0)) {
            throw new CheckedException("dayCapacityThreshold 必须为正整数");
        }
    }

    private Integer resolvePlanNum(String remark) {
        if (remark == null || !remark.contains("T+N=")) {
            return 7;
        }
        try {
            String value = remark.substring(remark.indexOf("T+N=") + 4).trim();
            int separator = value.indexOf('，');
            if (separator >= 0) {
                value = value.substring(0, separator).trim();
            }
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return 7;
        }
    }

    @Override
    public Page<PlnSchedulingGanttDetailVO> queryLockedGantt(SearchPage page, String productCode,String productName, LocalDate startDate, LocalDate endDate) {
        LambdaQueryWrapper<PlnIssueEntity> wrapper = new LambdaQueryWrapper<PlnIssueEntity>()
                .eq(PlnIssueEntity::getLockStatus, Boolean.TRUE)
                .like(productCode != null && !productCode.isBlank(), PlnIssueEntity::getProductCode, productCode)
                .like(productName != null && !productName.isBlank(), PlnIssueEntity::getProductName,productName)
                .ge(startDate != null, PlnIssueEntity::getScheduledDate, startDate)
                .le(endDate != null, PlnIssueEntity::getScheduledDate, endDate)
                .orderByAsc(PlnIssueEntity::getScheduledDate)
                .orderByAsc(PlnIssueEntity::getDemandTime);

        Page<PlnIssueEntity> issuePage = plnIssueMapper.selectPage(page.toMpPage(), wrapper);
        Page<PlnSchedulingGanttDetailVO> result = new Page<>(issuePage.getCurrent(), issuePage.getSize(), issuePage.getTotal());
        result.setRecords(issuePage.getRecords().stream().map(issue -> {
            PlnSchedulingGanttDetailVO vo = new PlnSchedulingGanttDetailVO();
            vo.setIssueId(issue.getId());
            vo.setIssueNo(issue.getIssueNo());
            vo.setCustomerCode(issue.getCustomerCode());
            vo.setCustomerName(issue.getCustomerName());
            vo.setProductCode(issue.getProductCode());
            vo.setProductName(issue.getProductName());
            vo.setSpecificationModel(issue.getSpecificationModel());
            vo.setNum(issue.getNum());
            vo.setDemandTime(issue.getDemandTime());
            vo.setScheduledDate(issue.getScheduledDate());
            vo.setScheduledEndDate(issue.getScheduledEndDate());
            vo.setIssueTime(issue.getIssueTime());
            vo.setStatus(issue.getStatus());
            vo.setLockStatus(issue.getLockStatus());
            vo.setPlanType(issue.getPlanType());
            return vo;
        }).collect(Collectors.toList()));
        return result;
    }

    /**
     * 与 PlnSchedulingEngineServiceImpl.resolveDayCapacityThreshold 保持一致逻辑。
     */
    private int resolveDayCapacityThreshold(String ruleCode) {
        if (ruleCode == null || ruleCode.isBlank()) {
            return 3;
        }
        try {
            PlnSchedulingRuleSetEntity ruleSet = this.getOne(
                    new LambdaQueryWrapper<PlnSchedulingRuleSetEntity>()
                            .eq(PlnSchedulingRuleSetEntity::getRuleCode, ruleCode)
                            .eq(PlnSchedulingRuleSetEntity::getActive, Boolean.TRUE)
                            .last("LIMIT 1"));
            if (ruleSet == null || ruleSet.getRuleContent() == null) {
                return 3;
            }
            com.fasterxml.jackson.databind.JsonNode node = OBJECT_MAPPER.readTree(ruleSet.getRuleContent());
            com.fasterxml.jackson.databind.JsonNode threshold = node.get("dayCapacityThreshold");
            if (threshold != null && threshold.isInt() && threshold.asInt() > 0) {
                return threshold.asInt();
            }
        } catch (Exception ignored) {
            // 解析失败降级
        }
        return 3;
    }
}
