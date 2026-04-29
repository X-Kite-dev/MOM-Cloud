package com.kitex.mom.plan.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.constant.CommonConstants;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmCalendarEntity;
import com.kitex.mom.mdm.entity.MdmClassesEntity;
import com.kitex.mom.mdm.entity.MdmProcessRouteEntity;
import com.kitex.mom.mdm.feign.RemoteCalendarService;
import com.kitex.mom.mdm.feign.RemoteClassesService;
import com.kitex.mom.mdm.feign.RemoteProcessRouteService;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.MesTaskReceiveDTO;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.plan.constant.IssueStatusEnum;
import com.kitex.mom.plan.constant.ModuleEnum;
import com.kitex.mom.plan.constant.UpdateTypeEnum;
import com.kitex.mom.plan.dto.PlnIssueModuleDTO;
import com.kitex.mom.plan.dto.PlnSchedulingReplanDTO;
import com.kitex.mom.plan.dto.PlnSchedulingRunDTO;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.PlnIssueMapper;
import com.kitex.mom.plan.mapper.PlnProductDemandMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingConflictMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingTaskMapper;
import com.kitex.mom.plan.service.PlanMatMrpService;
import com.kitex.mom.plan.service.PlnSchedulingEngineService;
import com.kitex.mom.plan.service.PlnUpdateLogService;
import com.kitex.mom.plan.service.SchedulingContext;
import com.kitex.mom.plan.service.conflict.ConflictEngine;
import com.kitex.mom.plan.service.matcher.RuleMatchResult;
import com.kitex.mom.plan.service.matcher.RuleMatcher;
import com.kitex.mom.plan.service.selector.RuleSetSelector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class)
public class PlnSchedulingEngineServiceImpl implements PlnSchedulingEngineService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 每天计划生产小时数（默认8小时工作制）
     */
    private static final int WORK_HOURS_PER_DAY = 8;
    /**
     * 计划开工时刻（当天 08:00）
     */
    private static final int PLAN_START_HOUR = 8;

    private final PlnSchedulingTaskMapper plnSchedulingTaskMapper;
    private final PlnProductDemandMapper plnProductDemandMapper;
    private final PlnIssueMapper plnIssueMapper;
    private final PlnSchedulingConflictMapper plnSchedulingConflictMapper;
    private final com.kitex.mom.plan.mapper.PlnSchedulingTaskDetailMapper plnSchedulingTaskDetailMapper;
    private final PlnUpdateLogService plnUpdateLogService;
    private final RemoteMesTaskService remoteTaskService;
    private final PlanMatMrpService planMatMrpService;
    private final RuleSetSelector ruleSetSelector;
    private final ConflictEngine conflictEngine;
    private final RemoteProcessRouteService remoteProcessRouteService;
    private final RemoteCalendarService remoteCalendarService;
    private final RemoteClassesService remoteClassesService;
    private final com.kitex.mom.plan.mapper.PlnSchedulingRuleSetMapper plnSchedulingRuleSetMapper;
    private final RuleMatcher ruleMatcher = new RuleMatcher();

    @Override
    public Boolean run(PlnSchedulingRunDTO runDTO) {
        if (runDTO == null) {
            throw new CheckedException("排产运行参数不能为空");
        }
        if ("simulate".equalsIgnoreCase(runDTO.getMode())) {
            return schedulingTaskOnly(runDTO);
        }
        if ("official".equalsIgnoreCase(runDTO.getMode())) {
            return writeOfficialIssue(runDTO);
        }
        throw new CheckedException("运行模式仅支持 simulate 或 official");
    }

    @Override
    public Boolean replan(PlnSchedulingReplanDTO replanDTO) {
        if (replanDTO == null) {
            throw new CheckedException("重排目标不能为空");
        }
        List<Long> targetIds = normalizeTargetIds(replanDTO.getIssueId(), replanDTO.getTargetIds());
        List<PlnIssueEntity> issues = plnIssueMapper.selectBatchIds(targetIds);
        if (issues == null || issues.isEmpty()) {
            throw new CheckedException("计划不存在");
        }
        if (issues.size() != targetIds.size()) {
            throw new CheckedException("存在无效重排目标");
        }
        boolean hasClosed = issues.stream().anyMatch(issue -> "3".equals(issue.getStatus()) || "4".equals(issue.getStatus()));
        if (hasClosed) {
            throw new CheckedException("已完成或已关闭计划禁止重排");
        }

        PlnSchedulingRuleSetEntity activeRule = requiredActiveRule();
        LocalDate baseDate = LocalDate.now();
        LocalDate endDate = baseDate.plusDays(Math.max(0, issues.size() - 1L));
        List<PlnIssueEntity> scheduledPlans = filterAndSortByRule(issues, activeRule, baseDate, endDate);
        if (scheduledPlans.isEmpty()) {
            throw new CheckedException("规则过滤后无可重排计划");
        }

        PlnSchedulingRunDTO runDTO = new PlnSchedulingRunDTO();
        runDTO.setMode("official");
        runDTO.setTriggerType("MANUAL");
        runDTO.setCycleType("DAY");
        runDTO.setScheduleDate(baseDate);

        PlnSchedulingTaskEntity task = buildTask(runDTO, "WAIT_CONFIRM", activeRule, scheduledPlans.size(), baseDate);
        task.setRemark("重排任务，目标数=" + targetIds.size() + "，待确认锁定");
        int inserted = plnSchedulingTaskMapper.insert(task);
        if (inserted <= 0) {
            throw new CheckedException("重排任务写入失败");
        }

        for (PlnIssueEntity issue : scheduledPlans) {
            issue.setPlanType(activeRule.getRuleCode());
        }
        snapshotTaskDetails(task.getId(), scheduledPlans);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public Boolean issueToModule(PlnIssueModuleDTO plnIssueModuleDTO) {
        List<PlnIssueEntity> plnIssueEntities = plnIssueMapper.selectBatchIds(Arrays.asList(plnIssueModuleDTO.getIds()));

        List<PlnUpdateLogEntity> updateLogs = plnIssueEntities.stream()
                .filter(entity -> !"1".equals(entity.getStatus())) // 幂等：已下发的跳过
                .map(entity -> {
                    PlnUpdateLogEntity updateLog = createUpdateLog(entity, UpdateTypeEnum.STATUS_UPDATE.getCode(), entity.getStatus(), "1");
                    entity.setStatus("1");
                    return updateLog;
                }).toList();

        if (updateLogs.isEmpty()) {
            return Boolean.TRUE; // 全部已下发，直接返回
        }
        plnUpdateLogService.saveUpdateLog(updateLogs);

        // 只处理本次状态变更的计划（status 已被设为 "1"）
        List<PlnIssueEntity> toIssue = plnIssueEntities.stream()
                .filter(e -> "1".equals(e.getStatus()))
                .toList();

        for (PlnIssueEntity plnIssueEntity : toIssue) {
            plnIssueMapper.updateById(plnIssueEntity);
        }

        Map<String, PlanMatMrpEntity> matMrpMap = planMatMrpService.list(new LambdaQueryWrapper<PlanMatMrpEntity>()
                        .in(PlanMatMrpEntity::getPlanDemandNo, toIssue.stream().map(PlnIssueEntity::getIssueNo).toList()))
                .stream()
                .collect(Collectors.toMap(entity -> entity.getPlanDemandNo() + "_" + entity.getMatCode(), Function.identity(), (v1, v2) -> v1));

        for (PlnIssueEntity plnIssueEntity : toIssue) {
            saveMesPlan(plnIssueEntity, plnIssueModuleDTO.getWorkshopName(), plnIssueModuleDTO.getWorkshopCode(),
                    matMrpMap.get(plnIssueEntity.getIssueNo() + "_" + plnIssueEntity.getProductCode()));
        }

        return Boolean.TRUE;
    }

    private Boolean schedulingTaskOnly(PlnSchedulingRunDTO runDTO) {
        LocalDate baseDate = resolveScheduleDate(runDTO);
        LocalDate startDate = getCycleStartDate(runDTO.getCycleType(), baseDate);
        LocalDate endDate = getCycleEndDate(runDTO.getCycleType(), baseDate, runDTO.getPlanNum());
        List<PlnIssueEntity> plans = listUnissuedPlansInRange(startDate, endDate, normalizeRunTargetIds(runDTO.getTargetIds()));
        int planCount = plans.size();
        if (planCount <= 0) {
            throw new CheckedException("当前周期无可排产计划");
        }
        PlnSchedulingRuleSetEntity activeRule = requiredActiveRule();
        LocalDate scheduleStart = resolveScheduleStartDate(runDTO.getCycleType(), baseDate);
        PlnSchedulingTaskEntity task = buildTask(runDTO, "PENDING", activeRule, planCount, scheduleStart);
        task.setRemark("模拟排产范围: " + startDate + " ~ " + endDate + "，计划数=" + planCount + (runAsTnRange(runDTO.getCycleType()) ? "，T+N=" + (runDTO.getPlanNum() == null ? defaultPlanNum() : Math.max(1, runDTO.getPlanNum())) : ""));
        int inserted = plnSchedulingTaskMapper.insert(task);
        if (inserted > 0) {
            List<PlnIssueEntity> scheduledPlans = filterAndSortByRule(plans, activeRule, scheduleStart, endDate);
            task.setPlanCount(scheduledPlans.size());
            plnSchedulingTaskMapper.updateById(task);
            snapshotTaskDetails(task.getId(), scheduledPlans);
        }
        return inserted > 0;
    }

    private Boolean writeOfficialIssue(PlnSchedulingRunDTO runDTO) {
        LocalDate baseDate = resolveScheduleDate(runDTO);
        LocalDate startDate = getCycleStartDate(runDTO.getCycleType(), baseDate);
        LocalDate endDate = getCycleEndDate(runDTO.getCycleType(), baseDate, runDTO.getPlanNum());
        List<PlnIssueEntity> plans = listUnissuedPlansInRange(startDate, endDate, normalizeRunTargetIds(runDTO.getTargetIds()));
        if (plans == null || plans.isEmpty()) {
            throw new CheckedException("当前周期无可排产计划");
        }
        PlnSchedulingRuleSetEntity activeRule = requiredActiveRule();
        LocalDate scheduleStart = resolveScheduleStartDate(runDTO.getCycleType(), baseDate);
        List<PlnIssueEntity> scheduledPlans = filterAndSortByRule(plans, activeRule, scheduleStart, endDate);
        if (scheduledPlans == null || scheduledPlans.isEmpty()) {
            throw new CheckedException("规则过滤后无可排产计划");
        }

        PlnSchedulingTaskEntity task = buildTask(runDTO, "WAIT_CONFIRM", activeRule, scheduledPlans.size(), scheduleStart);
        task.setRemark("正式排产范围: " + startDate + " ~ " + endDate + "，计划数=" + scheduledPlans.size() + (runAsTnRange(runDTO.getCycleType()) ? "，T+N=" + (runDTO.getPlanNum() == null ? defaultPlanNum() : Math.max(1, runDTO.getPlanNum())) : "") + "，待确认锁定");
        int taskInserted = plnSchedulingTaskMapper.insert(task);
        if (taskInserted <= 0) {
            throw new CheckedException("正式排产任务写入失败");
        }

        for (PlnIssueEntity issue : scheduledPlans) {
            issue.setPlanType(activeRule != null ? activeRule.getRuleCode() : issue.getPlanType());
        }

        snapshotTaskDetails(task.getId(), scheduledPlans);
        return true;
    }

    @Override
    public Boolean confirmOfficialTaskLock(Long taskId) {
        if (taskId == null) {
            throw new CheckedException("任务ID不能为空");
        }
        PlnSchedulingTaskEntity task = plnSchedulingTaskMapper.selectById(taskId);
        if (task == null) {
            throw new CheckedException("任务不存在");
        }
        if (!"official".equalsIgnoreCase(task.getMode())) {
            throw new CheckedException("仅正式排产任务支持确认锁定");
        }
        if (!"WAIT_CONFIRM".equalsIgnoreCase(task.getTaskStatus())) {
            throw new CheckedException("当前任务状态不允许确认锁定");
        }

        List<com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity> details = plnSchedulingTaskDetailMapper.selectList(
                new LambdaQueryWrapper<com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity>()
                        .eq(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getTaskId, taskId)
                        .orderByAsc(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getDemandTime)
                        .orderByAsc(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getIssueNo)
        );
        if (details == null || details.isEmpty()) {
            throw new CheckedException("任务明细为空，无法确认锁定");
        }

        List<PlnSchedulingConflictEntity> engineConflicts = conflictEngine.detect(taskId, details);
        Set<Long> blockedIssueIds = new HashSet<>();
        // 幂等去重：预加载当前任务已存在的冲突记录，防止重入时重复插入
        Set<String> existingConflictKeys = plnSchedulingConflictMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingConflictEntity>()
                        .eq(PlnSchedulingConflictEntity::getTaskId, taskId)
                        .select(PlnSchedulingConflictEntity::getIssueId,
                                PlnSchedulingConflictEntity::getConflictType))
                .stream()
                .map(c -> taskId + "#" + c.getIssueId() + "#" + c.getConflictType())
                .collect(Collectors.toCollection(HashSet::new));
        int successCount = 0;
        int conflictCount = 0;
        int alreadyLockedCount = 0;
        if (engineConflicts != null && !engineConflicts.isEmpty()) {
            for (PlnSchedulingConflictEntity conflict : engineConflicts) {
                String dedupKey = taskId + "#" + conflict.getIssueId() + "#" + conflict.getConflictType();
                if (!existingConflictKeys.add(dedupKey)) {
                    continue;
                }
                plnSchedulingConflictMapper.insert(conflict);
                if (Boolean.TRUE.equals(conflict.getHardConstraint()) && conflict.getIssueId() != null) {
                    blockedIssueIds.add(conflict.getIssueId());
                }
                conflictCount++;
            }
        }

        int dayCapacityThreshold = resolveDayCapacityThreshold(task);
        for (com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity detail : details) {
            if (detail.getIssueId() != null && blockedIssueIds.contains(detail.getIssueId())) {
                continue;
            }
            PlnIssueEntity issue = plnIssueMapper.selectById(detail.getIssueId());
            if (issue == null) {
                String dedupKey = taskId + "#" + detail.getIssueId() + "#ISSUE_NOT_FOUND";
                if (existingConflictKeys.add(dedupKey)) {
                    PlnSchedulingConflictEntity conflict = new PlnSchedulingConflictEntity();
                    conflict.setTaskId(taskId);
                    conflict.setIssueId(detail.getIssueId());
                    conflict.setIssueNo(detail.getIssueNo());
                    conflict.setProductCode(detail.getProductCode());
                    conflict.setConflictType("ISSUE_NOT_FOUND");
                    conflict.setConflictLevel("HIGH");
                    conflict.setHardConstraint(Boolean.TRUE);
                    conflict.setConflictContent("计划" + detail.getIssueNo() + "不存在或已删除，无法锁定");
                    conflict.setResolved(Boolean.FALSE);
                    plnSchedulingConflictMapper.insert(conflict);
                    conflictCount++;
                }
                continue;
            }
            LocalDate scheduledDate = detail.getScheduledDate();
            if (scheduledDate == null) {
                String dedupKey = taskId + "#" + detail.getIssueId() + "#SCHEDULE_DATE_MISSING";
                if (existingConflictKeys.add(dedupKey)) {
                    PlnSchedulingConflictEntity conflict = new PlnSchedulingConflictEntity();
                    conflict.setTaskId(taskId);
                    conflict.setConflictType("SCHEDULE_DATE_MISSING");
                    conflict.setConflictLevel("HIGH");
                    conflict.setHardConstraint(Boolean.TRUE);
                    conflict.setIssueId(detail.getIssueId());
                    conflict.setIssueNo(detail.getIssueNo());
                    conflict.setProductCode(detail.getProductCode());
                    conflict.setConflictContent("计划" + detail.getIssueNo() + "缺少分配日期，无法锁定");
                    conflict.setResolved(Boolean.FALSE);
                    plnSchedulingConflictMapper.insert(conflict);
                    conflictCount++;
                }
                continue;
            }
            if (Boolean.TRUE.equals(issue.getLockStatus()) && issue.getLockTaskId() != null && !taskId.equals(issue.getLockTaskId())) {
                // 计划已被其他任务锁定，跳过但单独计数，不算冲突也不算成功
                alreadyLockedCount++;
                continue;
            }
            if (detail.getDemandTime() != null && scheduledDate.isAfter(detail.getDemandTime())) {
                String dedupKey = taskId + "#" + detail.getIssueId() + "#SCHEDULE_AFTER_DEMAND_DATE";
                if (existingConflictKeys.add(dedupKey)) {
                    PlnSchedulingConflictEntity conflict = new PlnSchedulingConflictEntity();
                    conflict.setTaskId(taskId);
                    conflict.setConflictType("SCHEDULE_AFTER_DEMAND_DATE");
                    conflict.setConflictLevel("HIGH");
                    conflict.setHardConstraint(Boolean.TRUE);
                    conflict.setIssueId(detail.getIssueId());
                    conflict.setIssueNo(detail.getIssueNo());
                    conflict.setProductCode(detail.getProductCode());
                    conflict.setConflictContent("计划" + detail.getIssueNo() + "分配日期" + scheduledDate + "晚于需求日期" + detail.getDemandTime() + "，禁止锁定");
                    conflict.setResolved(Boolean.FALSE);
                    plnSchedulingConflictMapper.insert(conflict);
                    conflictCount++;
                }
                continue;
            }
            Long sameDayCount = plnIssueMapper.selectCount(new LambdaQueryWrapper<PlnIssueEntity>()
                    .eq(PlnIssueEntity::getLockStatus, true)
                    .eq(PlnIssueEntity::getProductCode, detail.getProductCode())
                    .eq(PlnIssueEntity::getScheduledDate, scheduledDate)
                    .ne(PlnIssueEntity::getId, detail.getIssueId()));
            if (sameDayCount != null && sameDayCount >= dayCapacityThreshold) {
                String dedupKey = taskId + "#" + detail.getIssueId() + "#PRODUCT_DAY_CAPACITY_EXCEEDED";
                if (existingConflictKeys.add(dedupKey)) {
                    PlnSchedulingConflictEntity conflict = new PlnSchedulingConflictEntity();
                    conflict.setTaskId(taskId);
                    conflict.setConflictType("PRODUCT_DAY_CAPACITY_EXCEEDED");
                    conflict.setConflictLevel("MEDIUM");
                    conflict.setHardConstraint(Boolean.FALSE);
                    conflict.setIssueId(detail.getIssueId());
                    conflict.setIssueNo(detail.getIssueNo());
                    conflict.setProductCode(detail.getProductCode());
                    conflict.setConflictContent("产品" + detail.getProductCode() + "在" + scheduledDate + "当天排产数量超过阈值" + dayCapacityThreshold);
                    conflict.setResolved(Boolean.FALSE);
                    plnSchedulingConflictMapper.insert(conflict);
                    conflictCount++;
                }
            }

            issue.setType("issue");
            issue.setPlanType(task.getRuleCode() != null ? task.getRuleCode() : issue.getPlanType());
            issue.setScheduledDate(scheduledDate);
            issue.setIssueTime(scheduledDate.atStartOfDay());
            issue.setLockStatus(Boolean.TRUE);
            issue.setLockTaskId(taskId);
            issue.setLockTime(LocalDateTime.now());
            plnIssueMapper.updateById(issue);

            plnSchedulingTaskDetailMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity>()
                    .eq(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getTaskId, taskId)
                    .eq(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getIssueId, detail.getIssueId())
                    .set(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getLockStatus, true)
                    .set(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getLockTaskId, taskId)
                    .set(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getLockTime, issue.getLockTime())
                    .set(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getType, "issue")
                    .set(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getPlanType, issue.getPlanType()));

            // 若该计划已下发 MES（status=1），同步更新 MES 工单计划时间
            if ("1".equals(issue.getStatus())) {
                syncMesPlanDate(issue);
            }
            successCount++;
        }

        // successCount=0 且全部被其他任务锁定：该任务已无效，直接抛异常
        if (successCount == 0 && conflictCount == 0 && alreadyLockedCount > 0) {
            task.setTaskStatus("PARTIAL");
            task.setRemark((task.getRemark() == null ? "" : task.getRemark())
                    + "，确认锁定=0，已被其他任务锁定=" + alreadyLockedCount + "，该任务已失效");
            plnSchedulingTaskMapper.updateById(task);
            throw new CheckedException("该任务所有计划（共" + alreadyLockedCount + "条）已被其他排产任务锁定，无需重复确认");
        }
        // planCount 保持任务创建时的原始值，不覆盖，仅追加 remark
        task.setTaskStatus(conflictCount > 0 ? "PARTIAL" : "SUCCESS");
        task.setRemark((task.getRemark() == null ? "" : task.getRemark())
                + "，确认锁定=" + successCount + "，冲突=" + conflictCount
                + (alreadyLockedCount > 0 ? "，已被其他任务锁定(跳过)=" + alreadyLockedCount : ""));
        plnSchedulingTaskMapper.updateById(task);
        return true;
    }

    /**
     * 重排确认锁定后，同步更新 MES 工单的计划开工/完工时间。
     * 失败只记录警告日志，不影响排产主流程。
     */
    private void syncMesPlanDate(PlnIssueEntity issue) {
        try {
            LocalDate scheduledDate = nextWorkDay(issue.getScheduledDate() != null
                    ? issue.getScheduledDate() : LocalDate.now());
            List<MdmClassesEntity> shifts = queryActiveShifts();
            LocalTime firstShiftStart = shifts.isEmpty()
                    ? LocalTime.of(PLAN_START_HOUR, 0) : shifts.get(0).getStartTime();
            LocalDateTime planStart = scheduledDate.atTime(firstShiftStart);
            LocalDateTime planEnd = resolvePlanEndDate(
                    issue.getProductCode(), issue.getNum(), planStart,
                    issue.getDemandTime());

            MesTaskEntity update = new MesTaskEntity();
            update.setPlanNo(issue.getIssueNo());
            update.setPlanStartDate(planStart);
            update.setPlanEndDate(planEnd);
            R<Void> resp = remoteTaskService.update(update);
            if (resp == null || !ObjUtil.equals(CommonConstants.SUCCESS, resp.getCode())) {
                log.warn("[重排同步] MES 工单更新失败，planNo={}，忽略继续。", issue.getIssueNo());
            }
        } catch (Exception e) {
            log.warn("[重排同步] MES 工单更新异常，planNo={}，原因: {}", issue.getIssueNo(), e.getMessage());
        }
    }

    private PlnUpdateLogEntity createUpdateLog(PlnIssueEntity issue, String updateType, String oldValue, String newValue) {
        PlnUpdateLogEntity updateLog = new PlnUpdateLogEntity();
        updateLog.setCustomerCode(issue.getCustomerCode());
        updateLog.setCustomerName(issue.getCustomerName());
        updateLog.setProductCode(issue.getProductCode());
        updateLog.setProductName(issue.getProductName());
        updateLog.setSpecificationModel(issue.getSpecificationModel());
        updateLog.setMaterialAttribute(issue.getMaterialAttribute());
        updateLog.setOperateTime(issue.getIssueTime());
        updateLog.setModule(ModuleEnum.MES.getCode());
        updateLog.setPlanType(issue.getPlanType());
        updateLog.setUpdateType(updateType);
        updateLog.setOldValue(oldValue);
        updateLog.setNewValue(newValue);
        return updateLog;
    }

    private void saveMesPlan(PlnIssueEntity plnIssueEntity, String workshopName, String workshopCode,
                             PlanMatMrpEntity matMrpEntity) {
        MesTaskReceiveDTO receive = new MesTaskReceiveDTO();
        receive.setPlanNo(plnIssueEntity.getIssueNo());
        receive.setMatCode(plnIssueEntity.getProductCode());
        receive.setMatName(plnIssueEntity.getProductName());
        receive.setMatSpec(plnIssueEntity.getSpecificationModel());
        receive.setNoticeNum(plnIssueEntity.getNum());
        receive.setWorkshopName(workshopName);
        receive.setWorkshopCode(workshopCode);
        receive.setFactoryCode(plnIssueEntity.getFactoryCode());
        if (ObjUtil.isNotEmpty(matMrpEntity)) {
            receive.setType(ObjUtil.equal(matMrpEntity.getSubitemBomId(), 0L) ? "PRODUCT" : "PART");
        } else {
            receive.setType("PRODUCT");
        }

        // 计划开工时间 = scheduledDate 当天第一班次开始，若为休息日则顺延到下一工作日
        LocalDate scheduledDate = plnIssueEntity.getScheduledDate() != null
                ? plnIssueEntity.getScheduledDate()
                : LocalDate.now();
        scheduledDate = nextWorkDay(scheduledDate);
        List<MdmClassesEntity> shifts = queryActiveShifts();
        LocalTime firstShiftStart = shifts.isEmpty()
                ? LocalTime.of(PLAN_START_HOUR, 0)
                : shifts.get(0).getStartTime();
        LocalDateTime planStart = scheduledDate.atTime(firstShiftStart);
        receive.setPlanStartDate(toDate(planStart));

        // 计划完工时间：优先用工艺路线 productRate 计算，降级用需求日期 23:59
        LocalDateTime planEnd = resolvePlanEndDate(
                plnIssueEntity.getProductCode(),
                plnIssueEntity.getNum(),
                planStart,
                plnIssueEntity.getDemandTime()
        );
        receive.setPlanEndDate(toDate(planEnd));

        // 下发工艺路线编码（供 MES 自动绑定工艺路线）
        String processRouteCode = queryDefaultRouteCode(plnIssueEntity.getProductCode());
        receive.setProcessRouteCode(processRouteCode);

        R<Boolean> response = remoteTaskService.add(receive);
        if (response == null || !ObjUtil.equals(CommonConstants.SUCCESS, response.getCode())) {
            throw new CheckedException("生产模块推送失败");
        }
    }

    /**
     * 计算计划完工时间（APS 标准：按工作日历 + 班次累计有效工时）。
     * <pre>
     *   需求工时(h) = ceil(数量 / 小时产率)
     *   从 planStart 开始，按工作日历跳过休息日，
     *   在每个工作日内按班次时间窗口累计可用工时，
     *   直到累计工时 >= 需求工时，返回对应时刻。
     * </pre>
     * 降级顺序：
     * 1. 无工艺路线 / productRate=0  → demandTime 23:59
     * 2. 日历/班次查询异常           → planStart + 需求工时（简单加法）
     * 3. 无 demandTime               → planStart+1天 23:59
     */
    private LocalDateTime resolvePlanEndDate(String productCode, BigDecimal num,
                                             LocalDateTime planStart, LocalDate demandTime) {
        if (productCode == null || num == null || num.compareTo(BigDecimal.ZERO) <= 0) {
            return fallbackEndDate(planStart, demandTime);
        }
        try {
            MdmProcessRouteEntity route = queryDefaultRoute(productCode);
            if (route == null || route.getProductRate() == null
                    || route.getProductRate().compareTo(BigDecimal.ZERO) <= 0) {
                return fallbackEndDate(planStart, demandTime);
            }
            long neededHours = num.divide(route.getProductRate(), 0, RoundingMode.CEILING).longValue();
            neededHours = Math.max(1, neededHours);

            // 查询班次列表（全部启用班次，按开始时间排序）
            List<MdmClassesEntity> shifts = queryActiveShifts();
            if (shifts.isEmpty()) {
                // 无班次配置：简单加法降级
                return planStart.plusHours(neededHours);
            }

            // 按工作日历累计工时
            return calcEndByCalendar(planStart, neededHours, shifts);

        } catch (Exception e) {
            log.warn("[排产下发] 工时计算异常，productCode={}，降级简单加法。原因: {}", productCode, e.getMessage());
            return fallbackEndDate(planStart, demandTime);
        }
    }

    /**
     * 按工作日历 + 班次，从 start 开始累计 neededHours 小时，返回完工时刻。
     * 最多向后推算 365 天，防止死循环（极端情况：日历全休）。
     */
    private LocalDateTime calcEndByCalendar(LocalDateTime start, long neededHours,
                                            List<MdmClassesEntity> shifts) {
        // 预取 60 天日历（不够再扩展）
        LocalDate rangeStart = start.toLocalDate();
        LocalDate rangeEnd = rangeStart.plusDays(60);
        Map<LocalDate, Boolean> workDayMap = fetchWorkDayMap(rangeStart, rangeEnd);

        long remainMinutes = neededHours * 60;
        LocalDateTime cursor = start;
        int safeGuard = 0;

        while (remainMinutes > 0 && safeGuard < 365) {
            LocalDate day = cursor.toLocalDate();

            // 超出预取范围则扩展
            if (day.isAfter(rangeEnd)) {
                rangeEnd = day.plusDays(60);
                workDayMap.putAll(fetchWorkDayMap(day, rangeEnd));
            }

            // 判断是否工作日（日历有记录以日历为准，否则默认周一~周五为工作日）
            boolean isWorkDay = workDayMap.containsKey(day)
                    ? workDayMap.get(day)
                    : (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY);

            if (!isWorkDay) {
                cursor = day.plusDays(1).atTime(shifts.get(0).getStartTime());
                safeGuard++;
                continue;
            }

            // 在当天班次内累计可用工时
            for (MdmClassesEntity shift : shifts) {
                LocalDateTime shiftStart = day.atTime(shift.getStartTime());
                LocalDateTime shiftEnd = shift.getEndTime().isBefore(shift.getStartTime())
                        ? day.plusDays(1).atTime(shift.getEndTime())   // 跨天班次
                        : day.atTime(shift.getEndTime());

                // cursor 在班次结束之后，跳过
                if (!cursor.isBefore(shiftEnd)) continue;

                // cursor 在班次开始之前，移到班次开始
                LocalDateTime effectiveStart = cursor.isBefore(shiftStart) ? shiftStart : cursor;
                long availableMinutes = java.time.Duration.between(effectiveStart, shiftEnd).toMinutes();

                if (availableMinutes <= 0) continue;

                if (availableMinutes >= remainMinutes) {
                    return effectiveStart.plusMinutes(remainMinutes);
                }
                remainMinutes -= availableMinutes;
                cursor = shiftEnd;
            }

            // 当天班次用完，移到下一天第一个班次开始
            cursor = day.plusDays(1).atTime(shifts.get(0).getStartTime());
            safeGuard++;
        }

        // 安全兜底
        return start.plusHours(neededHours);
    }

    /**
     * 查询日期范围内工作日历，返回 date→isWorkDay 的 Map。
     * 查询失败返回空 Map，由调用方用默认周历降级。
     */
    private Map<LocalDate, Boolean> fetchWorkDayMap(LocalDate start, LocalDate end) {
        try {
            R<List<MdmCalendarEntity>> resp = remoteCalendarService.listByRange(start, end);
            if (resp == null || !ObjUtil.equals(CommonConstants.SUCCESS, resp.getCode())
                    || resp.getData() == null) {
                return Collections.emptyMap();
            }
            Map<LocalDate, Boolean> map = new HashMap<>();
            for (MdmCalendarEntity c : resp.getData()) {
                map.put(c.getTime(), "0".equals(c.getStatus())); // 0=上班
            }
            return map;
        } catch (Exception e) {
            log.warn("[排产下发] 查询工作日历失败，降级使用自然周历。原因: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 查询所有启用班次，按开始时间升序。
     */
    private List<MdmClassesEntity> queryActiveShifts() {
        try {
            R<List<MdmClassesEntity>> resp = remoteClassesService.listActive();
            if (resp == null || !ObjUtil.equals(CommonConstants.SUCCESS, resp.getCode())
                    || resp.getData() == null) {
                return Collections.emptyList();
            }
            return resp.getData().stream()
                    .filter(s -> s.getStartTime() != null && s.getEndTime() != null)
                    .sorted(Comparator.comparing(MdmClassesEntity::getStartTime))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[排产下发] 查询班次失败，降级无班次模式。原因: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private LocalDateTime fallbackEndDate(LocalDateTime planStart, LocalDate demandTime) {
        if (demandTime != null) {
            return demandTime.atTime(23, 59, 59);
        }
        return planStart.toLocalDate().plusDays(1).atTime(23, 59, 59);
    }

    /**
     * 计算每天总可用产能分钟数（所有启用班次时长之和）。
     * 跨天班次（endTime < startTime）按跨天计算。
     * 无班次配置时降级为 WORK_HOURS_PER_DAY * 60。
     */
    private long calcDailyCapacityMinutes(List<MdmClassesEntity> shifts) {
        if (shifts == null || shifts.isEmpty()) {
            return (long) WORK_HOURS_PER_DAY * 60;
        }
        long total = 0;
        for (MdmClassesEntity shift : shifts) {
            if (shift.getStartTime() == null || shift.getEndTime() == null) continue;
            long minutes = java.time.Duration.between(shift.getStartTime(), shift.getEndTime()).toMinutes();
            if (minutes <= 0) minutes += 24 * 60; // 跨天班次
            total += minutes;
        }
        return total > 0 ? total : (long) WORK_HOURS_PER_DAY * 60;
    }

    /**
     * 计算单条计划所需生产分钟数，同时返回警告码。
     * 返回 long[]{neededMinutes, warningCode}
     * warningCode: 0=正常, 1=无工艺路线(降级60min), 2=产率未配置(降级60min)
     */
    private long[] calcNeededMinutesWithWarn(PlnIssueEntity issue) {
        if (issue.getNum() == null || issue.getNum().compareTo(BigDecimal.ZERO) <= 0) {
            return new long[]{60, 1};
        }
        try {
            MdmProcessRouteEntity route = queryDefaultRoute(issue.getProductCode());
            if (route == null) {
                return new long[]{60, 1};
            }
            if (route.getProductRate() == null || route.getProductRate().compareTo(BigDecimal.ZERO) <= 0) {
                return new long[]{60, 2};
            }
            long hours = issue.getNum().divide(route.getProductRate(), 0, RoundingMode.CEILING).longValue();
            return new long[]{Math.max(1, hours) * 60, 0};
        } catch (Exception e) {
            log.warn("[排产] 工时计算失败，productCode={}，降级60分钟。原因: {}", issue.getProductCode(), e.getMessage());
            return new long[]{60, 1};
        }
    }

    private long calcNeededMinutes(PlnIssueEntity issue, List<MdmClassesEntity> shifts) {
        return calcNeededMinutesWithWarn(issue)[0];
    }

    /**
     * 从 startDate 到 endDate 范围内收集工作日列表。
     * 若工作日数量不足 minCount，则向后延伸直到凑够为止（最多额外找 180 天）。
     * 至少返回 1 个工作日（兜底用 startDate）。
     */
    private List<LocalDate> buildWorkDaySequence(LocalDate startDate, LocalDate endDate, int minCount) {
        LocalDate rangeEnd = endDate.plusDays(Math.max(0, minCount));  // 预留足够范围
        Map<LocalDate, Boolean> workDayMap = fetchWorkDayMap(startDate, rangeEnd.plusDays(180));
        if (workDayMap.isEmpty()) {
            SchedulingContext.setWarning("工作日历未配置，已按自然周历（周一至周五）排产");
        }
        List<LocalDate> result = new ArrayList<>();
        LocalDate cursor = startDate;
        int safeGuard = 0;
        while (result.size() < Math.max(1, minCount) && safeGuard < 365) {
            boolean isWork = workDayMap.containsKey(cursor)
                    ? workDayMap.get(cursor)
                    : (cursor.getDayOfWeek() != DayOfWeek.SATURDAY && cursor.getDayOfWeek() != DayOfWeek.SUNDAY);
            if (isWork) {
                result.add(cursor);
            }
            cursor = cursor.plusDays(1);
            safeGuard++;
        }
        if (result.isEmpty()) {
            result.add(startDate); // 极端兜底
        }
        return result;
    }

    private LocalDate nextWorkDay(LocalDate date) {
        Map<LocalDate, Boolean> workDayMap = fetchWorkDayMap(date, date.plusDays(30));
        for (int i = 0; i < 30; i++) {
            LocalDate candidate = date.plusDays(i);
            boolean isWork = workDayMap.containsKey(candidate)
                    ? workDayMap.get(candidate)
                    : (candidate.getDayOfWeek() != DayOfWeek.SATURDAY
                       && candidate.getDayOfWeek() != DayOfWeek.SUNDAY);
            if (isWork) return candidate;
        }
        return date; // 极端兜底
    }

    /**
     * 从排产任务关联的规则集 ruleContent 中读取 dayCapacityThreshold。
     * ruleContent 示例: {"dayCapacityThreshold": 5}
     * 读不到或解析失败时降级为默认值 3。
     */
    private int resolveDayCapacityThreshold(PlnSchedulingTaskEntity task) {
        if (task == null || task.getRuleCode() == null) {
            return 3;
        }
        try {
            PlnSchedulingRuleSetEntity ruleSet = plnSchedulingRuleSetMapper.selectOne(
                    new LambdaQueryWrapper<PlnSchedulingRuleSetEntity>()
                            .eq(PlnSchedulingRuleSetEntity::getRuleCode, task.getRuleCode())
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
        } catch (Exception e) {
            log.warn("[排产确认] 读取 dayCapacityThreshold 失败，降级为 3。原因: {}", e.getMessage());
        }
        return 3;
    }

    private MdmProcessRouteEntity queryDefaultRoute(String productCode) {
        if (productCode == null || productCode.isBlank()) {
            return null;
        }
        R<MdmProcessRouteEntity> resp = remoteProcessRouteService.getDefaultByProductCode(productCode);
        if (resp == null || !ObjUtil.equals(CommonConstants.SUCCESS, resp.getCode())) {
            return null;
        }
        return resp.getData();
    }

    /**
     * 查询产品默认工艺路线编码，找不到返回 null。
     */
    private String queryDefaultRouteCode(String productCode) {
        MdmProcessRouteEntity route = queryDefaultRoute(productCode);
        return route != null ? route.getRouteCode() : null;
    }

    /**
     * LocalDateTime → java.util.Date（时区 Asia/Shanghai）
     */
    private Date toDate(LocalDateTime ldt) {
        if (ldt == null) {
            return null;
        }
        return Date.from(ldt.atZone(ZoneId.of("Asia/Shanghai")).toInstant());
    }

    private PlnSchedulingTaskEntity buildTask(PlnSchedulingRunDTO runDTO,
                                              String status,
                                              PlnSchedulingRuleSetEntity activeRule,
                                              int planCount,
                                              LocalDate scheduleDate) {
        PlnSchedulingTaskEntity task = new PlnSchedulingTaskEntity();
        task.setTaskNo(buildTaskNo(runDTO.getMode(), runDTO.getCycleType(), scheduleDate));
        task.setMode(runDTO.getMode());
        task.setTriggerType((runDTO.getTriggerType() == null || runDTO.getTriggerType().isBlank()) ? "MANUAL" : runDTO.getTriggerType());
        task.setCycleType(runDTO.getCycleType());
        task.setScheduleDate(scheduleDate);
        task.setTaskStatus(status);
        task.setPlanCount(planCount);
        if (activeRule != null) {
            task.setRuleCode(activeRule.getRuleCode());
            task.setRuleVersion(activeRule.getRuleVersion());
        } else {
            task.setRuleCode("DEFAULT_RULE");
            task.setRuleVersion(1);
        }
        return task;
    }

    private String buildTaskNo(String mode, String cycleType, LocalDate scheduleDate) {
        String modeCode = "simulate".equalsIgnoreCase(mode) ? "SIM" : "OFF";
        String cycleCode = "DAY";
        if ("WEEK".equalsIgnoreCase(cycleType)) {
            cycleCode = "WEEK";
        } else if ("MONTH".equalsIgnoreCase(cycleType)) {
            cycleCode = "MONTH";
        } else if (runAsTnRange(cycleType)) {
            cycleCode = "TN";
        }
        String dateCode = (scheduleDate == null ? LocalDate.now() : scheduleDate)
                .format(DateTimeFormatter.BASIC_ISO_DATE);
        String timeCode = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));
        String randomCode = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return "TASK-" + modeCode + "-" + cycleCode + "-" + dateCode + "-" + timeCode + "-" + randomCode;
    }

    private LocalDate resolveScheduleDate(PlnSchedulingRunDTO runDTO) {
        return runDTO.getScheduleDate() == null ? LocalDate.now() : runDTO.getScheduleDate();
    }

    private LocalDate getCycleStartDate(String cycleType, LocalDate baseDate) {
        if ("WEEK".equalsIgnoreCase(cycleType)) {
            return baseDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        } else if ("MONTH".equalsIgnoreCase(cycleType)) {
            return baseDate.withDayOfMonth(1);
        } else {
            return baseDate;
        }
    }

    /**
     * 排产分配的实际起始工作日（不早于今天）。
     * 查询计划范围用 getCycleStartDate，分配排产日期用此方法。
     */
    private LocalDate resolveScheduleStartDate(String cycleType, LocalDate baseDate) {
        LocalDate cycleStart = getCycleStartDate(cycleType, baseDate);
        LocalDate today = LocalDate.now();
        return cycleStart.isBefore(today) ? today : cycleStart;
    }

    private LocalDate getCycleEndDate(String cycleType, LocalDate baseDate, Integer planNum) {
        if ("WEEK".equalsIgnoreCase(cycleType)) {
            return baseDate.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        }
        if ("MONTH".equalsIgnoreCase(cycleType)) {
            return baseDate.with(TemporalAdjusters.lastDayOfMonth());
        }
        if (runAsTnRange(cycleType)) {
            int days = planNum == null ? defaultPlanNum() : Math.max(1, planNum);
            return baseDate.plusDays(days - 1L);
        }
        return baseDate;
    }

    private List<PlnIssueEntity> listUnissuedPlansInRange(LocalDate startDate, LocalDate endDate, List<Long> targetIds) {
        LambdaQueryWrapper<PlnIssueEntity> queryWrapper = new LambdaQueryWrapper<PlnIssueEntity>()
                .eq(PlnIssueEntity::getStatus, IssueStatusEnum.UNISSUED.getCode())
                .and(wrapper -> wrapper.isNull(PlnIssueEntity::getLockStatus).or().eq(PlnIssueEntity::getLockStatus, false))
                .and(wrapper -> wrapper
                        .between(PlnIssueEntity::getDemandTime, startDate, endDate)
                        .or()
                        .between(PlnIssueEntity::getScheduledDate, startDate, endDate));
        if (targetIds != null && !targetIds.isEmpty()) {
            queryWrapper.in(PlnIssueEntity::getId, targetIds);
        }
        return plnIssueMapper.selectList(queryWrapper
                .orderByAsc(PlnIssueEntity::getDemandTime)
                .orderByAsc(PlnIssueEntity::getIssueTime)
                .orderByAsc(PlnIssueEntity::getId));
    }

    private List<Long> normalizeTargetIds(Long issueId, List<Long> targetIds) {
        if (targetIds != null && !targetIds.isEmpty()) {
            List<Long> normalized = targetIds.stream().filter(Objects::nonNull).distinct().toList();
            if (normalized.isEmpty()) {
                throw new CheckedException("重排目标不能为空");
            }
            return normalized;
        }
        if (issueId != null) {
            return List.of(issueId);
        }
        throw new CheckedException("重排目标不能为空");
    }

    private List<Long> normalizeRunTargetIds(List<Long> targetIds) {
        if (targetIds == null || targetIds.isEmpty()) {
            return null;
        }
        List<Long> normalized = targetIds.stream().filter(Objects::nonNull).distinct().toList();
        return normalized.isEmpty() ? null : normalized;
    }

    private PlnSchedulingRuleSetEntity requiredActiveRule() {
        PlnSchedulingRuleSetEntity activeRule = ruleSetSelector.selectActiveRule();
        if (activeRule == null) {
            throw new CheckedException("未配置可用排产规则");
        }
        return activeRule;
    }

    private boolean runAsTnRange(String cycleType) {
        return "TN".equalsIgnoreCase(cycleType) || "T+N".equalsIgnoreCase(cycleType);
    }

    private List<PlnIssueEntity> filterAndSortByRule(List<PlnIssueEntity> plans,
                                                     PlnSchedulingRuleSetEntity activeRule,
                                                     LocalDate startDate,
                                                     LocalDate endDate) {
        if (plans == null || plans.isEmpty()) {
            return Collections.emptyList();
        }
        int totalDays = Math.max(1, (int) java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1);
        List<MatchCandidate> candidates = new ArrayList<>();
        int index = 0;
        for (PlnIssueEntity issue : plans) {
            // 先评分，不提前分配日期
            RuleMatchResult result = ruleMatcher.match(issue, activeRule);
            if (result.isPassed()) {
                issue.setMatchedRules(toJson(result.getMatchedRules()));
                issue.setScoreBreakdown(toJson(result.getScoreBreakdown()));
                candidates.add(new MatchCandidate(issue, result.getSoftScore(), index));
            }
            index++;
        }
        // 按评分降序排列，评分相同时保持原始顺序
        candidates.sort(Comparator
                .comparingDouble(MatchCandidate::score).reversed()
                .thenComparingInt(MatchCandidate::sequence));
        // 按有限产能填充：根据工时负荷分配排产日期
        List<PlnIssueEntity> sorted = candidates.stream().map(MatchCandidate::issue).toList();
        List<MdmClassesEntity> shifts = queryActiveShifts();
        long dailyCapacityMinutes = calcDailyCapacityMinutes(shifts);
        List<LocalDate> workDays = buildWorkDaySequence(startDate, endDate, sorted.size());

        int workDayIndex = 0;
        long remainMinutesToday = dailyCapacityMinutes;
        for (PlnIssueEntity issue : sorted) {
            if (workDayIndex >= workDays.size()) {
                LocalDate lastDay = workDays.get(workDays.size() - 1);
                List<LocalDate> more = buildWorkDaySequence(lastDay.plusDays(1), lastDay.plusDays(30), 1);
                workDays = new ArrayList<>(workDays);
                workDays.addAll(more);
            }
            long[] minutesAndWarn = calcNeededMinutesWithWarn(issue);
            long neededMinutes = minutesAndWarn[0];
            long warnCode = minutesAndWarn[1];
            // 将降级警告追加到 scoreBreakdown
            if (warnCode != 0) {
                String warnMsg = warnCode == 1 ? "未配置工艺路线，工时按默认1小时计算" : "工艺路线产率未配置，工时按默认1小时计算";
                try {
                    java.util.Map<String, Object> breakdown = issue.getScoreBreakdown() != null
                            ? new java.util.LinkedHashMap<>(OBJECT_MAPPER.readValue(issue.getScoreBreakdown(), new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}))
                            : new java.util.LinkedHashMap<>();
                    breakdown.put("_warning", warnMsg);
                    issue.setScoreBreakdown(toJson(breakdown));
                } catch (Exception ignored) {}
            }
            // 当天剩余不足且不是当天第一条，换下一个工作日
            if (remainMinutesToday <= 0) {
                workDayIndex++;
                remainMinutesToday = dailyCapacityMinutes;
                if (workDayIndex >= workDays.size()) {
                    LocalDate lastDay = workDays.get(workDays.size() - 1);
                    List<LocalDate> more = buildWorkDaySequence(lastDay.plusDays(1), lastDay.plusDays(30), 1);
                    workDays = new ArrayList<>(workDays);
                    workDays.addAll(more);
                }
            }
            issue.setScheduledDate(workDays.get(workDayIndex));
            // 扣减本条工时，跨天时推进 workDayIndex
            remainMinutesToday -= neededMinutes;
            while (remainMinutesToday < 0) {
                workDayIndex++;
                remainMinutesToday += dailyCapacityMinutes;
                if (workDayIndex >= workDays.size()) {
                    LocalDate lastDay = workDays.get(workDays.size() - 1);
                    List<LocalDate> more = buildWorkDaySequence(lastDay.plusDays(1), lastDay.plusDays(30), 1);
                    workDays = new ArrayList<>(workDays);
                    workDays.addAll(more);
                }
            }
            // 恰好用完当天产能，下一条从新的一天开始
            if (remainMinutesToday == 0) {
                issue.setScheduledEndDate(workDays.get(workDayIndex));
                workDayIndex++;
                remainMinutesToday = dailyCapacityMinutes;
                if (workDayIndex >= workDays.size()) {
                    LocalDate lastDay = workDays.get(workDays.size() - 1);
                    List<LocalDate> more = buildWorkDaySequence(lastDay.plusDays(1), lastDay.plusDays(30), 1);
                    workDays = new ArrayList<>(workDays);
                    workDays.addAll(more);
                }
            } else {
                issue.setScheduledEndDate(workDays.get(workDayIndex));
            }
        }
        // 分配完日期后，检查 scheduledEndDate 是否超过 demandTime
        for (PlnIssueEntity issue : sorted) {
            LocalDate endDate2 = issue.getScheduledEndDate();
            LocalDate demandTime = issue.getDemandTime();
            if (endDate2 != null && demandTime != null && endDate2.isAfter(demandTime)) {
                try {
                    java.util.Map<String, Object> breakdown = issue.getScoreBreakdown() != null
                            ? new java.util.LinkedHashMap<>(OBJECT_MAPPER.readValue(issue.getScoreBreakdown(), new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}))
                            : new java.util.LinkedHashMap<>();
                    long overDays = java.time.temporal.ChronoUnit.DAYS.between(demandTime, endDate2);
                    breakdown.put("_endDateWarning", "预计完工" + endDate2 + "，超出需求日期" + overDays + "天");
                    issue.setScoreBreakdown(toJson(breakdown));
                } catch (Exception ignored) {}
            }
        }
        return sorted;
    }

    private record MatchCandidate(PlnIssueEntity issue, double score, int sequence) {
    }

    private void snapshotTaskDetails(Long taskId, List<PlnIssueEntity> plans) {
        if (taskId == null || plans == null || plans.isEmpty()) {
            return;
        }
        for (PlnIssueEntity issue : plans) {
            com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity detail = new com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity();
            detail.setTaskId(taskId);
            detail.setIssueId(issue.getId());
            detail.setIssueNo(issue.getIssueNo());
            detail.setCustomerCode(issue.getCustomerCode());
            detail.setCustomerName(issue.getCustomerName());
            detail.setProductCode(issue.getProductCode());
            detail.setProductName(issue.getProductName());
            detail.setSpecificationModel(issue.getSpecificationModel());
            detail.setNum(issue.getNum());
            detail.setIssueTime(issue.getIssueTime());
            detail.setDemandTime(issue.getDemandTime());
            detail.setScheduledDate(issue.getScheduledDate());
            detail.setScheduledEndDate(issue.getScheduledEndDate());
            detail.setLockStatus(issue.getLockStatus());
            detail.setLockTaskId(issue.getLockTaskId());
            detail.setLockTime(issue.getLockTime());
            detail.setStatus(issue.getStatus());
            detail.setType(issue.getType());
            detail.setPlanType(issue.getPlanType());
            detail.setMatchedRules(issue.getMatchedRules());
            detail.setScoreBreakdown(issue.getScoreBreakdown());
            detail.setFactoryCode(issue.getFactoryCode());
            // 快照工艺路线编码，用于 ConflictEngine 冲突检测（查询失败不影响主流程）
            try {
                detail.setProcessRouteCode(queryDefaultRouteCode(issue.getProductCode()));
            } catch (Exception ignored) {
                // 查询失败留空，ConflictEngine 会产出 ROUTE_NOT_ASSIGNED 软冲突
            }
            plnSchedulingTaskDetailMapper.insert(detail);
        }
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new CheckedException("规则解释信息序列化失败");
        }
    }

    @Override
    public LocalDate calcScheduledEndDate(String productCode, BigDecimal num, LocalDate startDate) {
        if (startDate == null) return LocalDate.now();
        if (productCode == null || num == null || num.compareTo(BigDecimal.ZERO) <= 0) {
            return startDate;
        }
        try {
            MdmProcessRouteEntity route = queryDefaultRoute(productCode);
            if (route == null || route.getProductRate() == null
                    || route.getProductRate().compareTo(BigDecimal.ZERO) <= 0) {
                return startDate;
            }
            long neededHours = num.divide(route.getProductRate(), 0, RoundingMode.CEILING).longValue();
            neededHours = Math.max(1, neededHours);
            List<MdmClassesEntity> shifts = queryActiveShifts();
            long dailyCapacityMinutes = calcDailyCapacityMinutes(shifts);
            if (dailyCapacityMinutes <= 0) return startDate;
            // 从 startDate 开始，按工作日顺延，直到消耗完所需工时
            long remainMinutes = neededHours * 60;
            List<LocalDate> workDays = buildWorkDaySequence(startDate, startDate.plusDays(Math.max(30, (int)(remainMinutes / dailyCapacityMinutes) + 5)), (int)(remainMinutes / dailyCapacityMinutes) + 2);
            int idx = 0;
            long remain = remainMinutes;
            while (remain > 0 && idx < workDays.size()) {
                remain -= dailyCapacityMinutes;
                if (remain > 0) idx++;
            }
            return workDays.get(Math.min(idx, workDays.size() - 1));
        } catch (Exception e) {
            log.warn("[排产调整] 结束日期计算失败，productCode={}，降级返回 startDate。原因: {}", productCode, e.getMessage());
            return startDate;
        }
    }

    private int defaultPlanNum() {
        return 7;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean savePlanDateBatch(List<PlnSchedulingTaskDetailEntity> PlnSchedulingTaskDetailEntity) {
        // 空集合直接返回
        if (CollUtil.isEmpty(PlnSchedulingTaskDetailEntity)) {
            return false;
        }
        // 批量更新
        return plnProductDemandMapper.updatePlanDateBatch(PlnSchedulingTaskDetailEntity) > 0;
    }
}
