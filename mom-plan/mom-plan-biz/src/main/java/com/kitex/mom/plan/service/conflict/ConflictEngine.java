package com.kitex.mom.plan.service.conflict;

import cn.hutool.core.util.StrUtil;
import com.kitex.mom.plan.entity.PlnSchedulingConflictEntity;
import com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 排产冲突检测引擎
 * <p>
 * 在 confirmOfficialTaskLock 锁定前对任务明细快照执行前置冲突分析，
 * 检测出的冲突会影响锁定策略（硬冲突阻断锁定，软冲突仅记录警告）。
 * <p>
 * 注意：此引擎仅分析当前任务批次内部冲突，
 * 与已锁定计划的交叉冲突由 confirmOfficialTaskLock 主流程负责检测。
 */
@Component
public class ConflictEngine {

    /**
     * 同一产品同一天批次内集中度阈值（超出则产出 SOFT 冲突警告）。
     * 此阈值关注批次内集中度，与 dayCapacityThreshold（已锁定库存量阈值）互补。
     */
    private static final int INTRA_BATCH_CONCENTRATION_THRESHOLD = 3;

    public List<PlnSchedulingConflictEntity> detect(Long taskId, List<PlnSchedulingTaskDetailEntity> taskDetails) {
        if (taskId == null || taskDetails == null || taskDetails.isEmpty()) {
            return List.of();
        }

        List<PlnSchedulingConflictEntity> result = new ArrayList<>();
        result.addAll(detectMaterialConstraints(taskId, taskDetails));
        result.addAll(detectOperationConstraints(taskId, taskDetails));
        result.addAll(detectSameProductSameDayConcentration(taskId, taskDetails));
        result.addAll(detectMissingProcessRoute(taskId, taskDetails));
        return result;
    }

    /**
     * 物料约束在正式锁定时属于硬约束。排产快照生成时已经评估一次，锁定前会重新读取MRP和WMS复核。
     */
    private List<PlnSchedulingConflictEntity> detectMaterialConstraints(
            Long taskId, List<PlnSchedulingTaskDetailEntity> taskDetails) {
        List<PlnSchedulingConflictEntity> conflicts = new ArrayList<>();
        for (PlnSchedulingTaskDetailEntity detail : taskDetails) {
            String status = StrUtil.blankToDefault(detail.getMaterialConstraintStatus(), "MRP_NOT_CALCULATED");
            String conflictType = null;
            if (Boolean.TRUE.equals(detail.getMaterialHardBlocked())) {
                conflictType = switch (status) {
                    case "SUPPLY_NOT_RELEASED" -> "MATERIAL_SUPPLY_NOT_RELEASED";
                    case "SUPPLY_OVERDUE" -> "MATERIAL_SUPPLY_OVERDUE";
                    case "SUPPLY_DATE_MISSING" -> "MATERIAL_SUPPLY_DATE_MISSING";
                    case "SUBSTITUTE_PENDING" -> "MATERIAL_SUBSTITUTE_PENDING";
                    case "POLICY_CONFLICT" -> "MATERIAL_POLICY_CONFLICT";
                    case "MULTILEVEL_SUBSTITUTE_RECALC_REQUIRED" -> "MATERIAL_MULTILEVEL_RECALC_REQUIRED";
                    case "RESERVATION_RISK" -> "MATERIAL_RESERVATION_RISK";
                    case "RESERVATION_UNVERIFIED" -> "MATERIAL_RESERVATION_UNVERIFIED";
                    case "SUPPLY_EVIDENCE_UNVERIFIED" -> "MATERIAL_SUPPLY_EVIDENCE_UNVERIFIED";
                    case "BOM_LINEAGE_INVALID" -> "MATERIAL_BOM_LINEAGE_INVALID";
                    case "MATERIAL_OPERATION_MAPPING_MISSING" -> "MATERIAL_OPERATION_MAPPING_MISSING";
                    case "MULTILEVEL_CONVERGENCE_CONFLICT" -> "MATERIAL_MULTILEVEL_CONVERGENCE_CONFLICT";
                    default -> "MATERIAL_MRP_NOT_CALCULATED";
                };
            } else if (detail.getMaterialAvailableDate() != null && detail.getScheduledDate() != null
                    && detail.getScheduledDate().isBefore(detail.getMaterialAvailableDate())) {
                conflictType = "MATERIAL_NOT_AVAILABLE_AT_START";
            }
            if (conflictType == null) {
                continue;
            }
            PlnSchedulingConflictEntity conflict = new PlnSchedulingConflictEntity();
            conflict.setTaskId(taskId);
            conflict.setIssueId(detail.getIssueId());
            conflict.setIssueNo(detail.getIssueNo());
            conflict.setProductCode(detail.getProductCode());
            conflict.setConflictType(conflictType);
            conflict.setConflictLevel("HIGH");
            conflict.setHardConstraint(Boolean.TRUE);
            String summary = StrUtil.blankToDefault(detail.getMaterialConstraintSummary(), "物料约束未通过");
            if ("MATERIAL_NOT_AVAILABLE_AT_START".equals(conflictType)) {
                summary = "计划开工日" + detail.getScheduledDate() + "早于预计齐料日"
                        + detail.getMaterialAvailableDate() + "；" + summary;
            }
            conflict.setConflictContent(summary);
            conflict.setResolved(Boolean.FALSE);
            conflict.setFactoryCode(detail.getFactoryCode());
            conflicts.add(conflict);
        }
        return conflicts;
    }

    /**
     * 工序级资源排程是正式锁定的硬边界，禁止缺路线、缺时间、缺资源或资源重叠的计划落锁。
     */
    private List<PlnSchedulingConflictEntity> detectOperationConstraints(
            Long taskId, List<PlnSchedulingTaskDetailEntity> taskDetails) {
        List<PlnSchedulingConflictEntity> conflicts = new ArrayList<>();
        for (PlnSchedulingTaskDetailEntity detail : taskDetails) {
            if (!Boolean.TRUE.equals(detail.getOperationHardBlocked())) {
                continue;
            }
            String status = StrUtil.blankToDefault(detail.getOperationScheduleStatus(), "OPERATION_SNAPSHOT_MISSING");
            String conflictType = switch (status) {
                case "ROUTE_MISSING" -> "OPERATION_ROUTE_MISSING";
                case "OPERATION_MISSING" -> "OPERATION_LIST_MISSING";
                case "RESOURCE_MISSING" -> "OPERATION_RESOURCE_MISSING";
                case "DURATION_MISSING" -> "OPERATION_DURATION_MISSING";
                case "CALENDAR_EXHAUSTED" -> "OPERATION_CALENDAR_EXHAUSTED";
                case "OPERATION_TIME_INVALID" -> "OPERATION_TIME_INVALID";
                case "PRECEDENCE_INVALID" -> "OPERATION_PRECEDENCE_INVALID";
                case "COMPLETION_AFTER_DEMAND" -> "OPERATION_COMPLETION_AFTER_DEMAND";
                case "RESOURCE_OVERLAP" -> "OPERATION_RESOURCE_OVERLAP";
                case "RESOURCE_INVALID" -> "OPERATION_RESOURCE_INVALID";
                case "RESOURCE_UNVERIFIED" -> "OPERATION_RESOURCE_UNVERIFIED";
                case "DEVICE_UNAVAILABLE" -> "OPERATION_DEVICE_UNAVAILABLE";
                case "DEVICE_UNVERIFIED" -> "OPERATION_DEVICE_UNVERIFIED";
                case "DEVICE_BINDING_INVALID" -> "OPERATION_DEVICE_BINDING_INVALID";
                case "WORKFORCE_MISSING" -> "OPERATION_WORKFORCE_MISSING";
                case "WORKFORCE_UNQUALIFIED" -> "OPERATION_WORKFORCE_UNQUALIFIED";
                case "WORKFORCE_UNAVAILABLE" -> "OPERATION_WORKFORCE_UNAVAILABLE";
                case "WORKFORCE_UNVERIFIED" -> "OPERATION_WORKFORCE_UNVERIFIED";
                case "WORKFORCE_BINDING_INVALID" -> "OPERATION_WORKFORCE_BINDING_INVALID";
                case "TOOLING_MISSING" -> "OPERATION_TOOLING_MISSING";
                case "TOOLING_UNQUALIFIED" -> "OPERATION_TOOLING_UNQUALIFIED";
                case "TOOLING_UNAVAILABLE" -> "OPERATION_TOOLING_UNAVAILABLE";
                case "TOOLING_UNVERIFIED" -> "OPERATION_TOOLING_UNVERIFIED";
                case "TOOLING_BINDING_INVALID" -> "OPERATION_TOOLING_BINDING_INVALID";
                case "TOOLING_LIFE_INSUFFICIENT" -> "OPERATION_TOOLING_LIFE_INSUFFICIENT";
                default -> "OPERATION_SNAPSHOT_MISSING";
            };
            PlnSchedulingConflictEntity conflict = new PlnSchedulingConflictEntity();
            conflict.setTaskId(taskId);
            conflict.setIssueId(detail.getIssueId());
            conflict.setIssueNo(detail.getIssueNo());
            conflict.setProductCode(detail.getProductCode());
            conflict.setConflictType(conflictType);
            conflict.setConflictLevel("HIGH");
            conflict.setHardConstraint(Boolean.TRUE);
            conflict.setConflictContent(StrUtil.blankToDefault(detail.getOperationScheduleSummary(),
                    "工序级有限产能排程未通过"));
            conflict.setResolved(Boolean.FALSE);
            conflict.setFactoryCode(detail.getFactoryCode());
            conflicts.add(conflict);
        }
        return conflicts;
    }

    /**
     * 检测批次内同产品同日集中度超限（SOFT 冲突）。
     * <p>
     * 同一天排产相同产品超过阈值，说明当日该产品计划集中，
     * 存在产能压力——记录软冲突供排产员人工确认，不阻断锁定。
     */
    private List<PlnSchedulingConflictEntity> detectSameProductSameDayConcentration(
            Long taskId, List<PlnSchedulingTaskDetailEntity> taskDetails) {

        // 按 productCode + scheduledDate 分组
        Map<String, List<PlnSchedulingTaskDetailEntity>> grouped = taskDetails.stream()
                .filter(d -> d.getProductCode() != null && d.getScheduledDate() != null)
                .collect(Collectors.groupingBy(
                        d -> d.getProductCode() + "|" + d.getScheduledDate()
                ));

        List<PlnSchedulingConflictEntity> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<PlnSchedulingTaskDetailEntity>> entry : grouped.entrySet()) {
            List<PlnSchedulingTaskDetailEntity> group = entry.getValue();
            if (group.size() <= INTRA_BATCH_CONCENTRATION_THRESHOLD) {
                continue;
            }
            // 超出阈值：为本组中首个明细产出一条汇总级软冲突
            PlnSchedulingTaskDetailEntity rep = group.get(0);
            String productCode = rep.getProductCode();
            LocalDate scheduledDate = rep.getScheduledDate();
            String issueNos = group.stream()
                    .map(PlnSchedulingTaskDetailEntity::getIssueNo)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("、"));

            PlnSchedulingConflictEntity conflict = new PlnSchedulingConflictEntity();
            conflict.setTaskId(taskId);
            conflict.setIssueId(rep.getIssueId());
            conflict.setIssueNo(rep.getIssueNo());
            conflict.setProductCode(productCode);
            conflict.setConflictType("SAME_PRODUCT_DAY_CONCENTRATION");
            conflict.setConflictLevel("LOW");
            conflict.setHardConstraint(Boolean.FALSE);
            conflict.setConflictContent(String.format(
                    "产品[%s]在[%s]批次内共排%d条（阈值%d），计划：%s，存在当日产能集中风险，建议分散至其他日期",
                    productCode, scheduledDate, group.size(), INTRA_BATCH_CONCENTRATION_THRESHOLD, issueNos));
            conflict.setResolved(Boolean.FALSE);
            conflict.setFactoryCode(rep.getFactoryCode());
            conflicts.add(conflict);
        }
        return conflicts;
    }

    /**
     * 检测未关联工艺路线的计划（SOFT 冲突）。
     * <p>
     * processRouteCode 为空说明该产品在 MDM 中未配置默认工艺路线，
     * 下发 MES 后工单将无法自动绑定工艺，需人工介入。
     * 产出软冲突警告，不阻断锁定。
     */
    private List<PlnSchedulingConflictEntity> detectMissingProcessRoute(
            Long taskId, List<PlnSchedulingTaskDetailEntity> taskDetails) {

        List<PlnSchedulingConflictEntity> conflicts = new ArrayList<>();
        // 相同产品只报一次，避免重复刷屏
        Set<String> reportedProductCodes = new HashSet<>();

        for (PlnSchedulingTaskDetailEntity detail : taskDetails) {
            String productCode = detail.getProductCode();
            if (productCode == null) {
                continue;
            }
            // 新任务已经由工序级排程将缺路线作为硬冲突处理；此分支只兼容历史任务快照。
            if (StrUtil.isNotBlank(detail.getOperationScheduleStatus())) {
                continue;
            }
            if (StrUtil.isNotBlank(detail.getProcessRouteCode())) {
                continue;
            }
            if (!reportedProductCodes.add(productCode)) {
                continue;
            }

            PlnSchedulingConflictEntity conflict = new PlnSchedulingConflictEntity();
            conflict.setTaskId(taskId);
            conflict.setIssueId(detail.getIssueId());
            conflict.setIssueNo(detail.getIssueNo());
            conflict.setProductCode(productCode);
            conflict.setConflictType("ROUTE_NOT_ASSIGNED");
            conflict.setConflictLevel("MEDIUM");
            conflict.setHardConstraint(Boolean.FALSE);
            conflict.setConflictContent(String.format(
                    "产品[%s]未配置默认工艺路线，下发MES后工单将无法自动绑定工序，请在MDM中维护工艺路线并设为默认", productCode));
            conflict.setResolved(Boolean.FALSE);
            conflict.setFactoryCode(detail.getFactoryCode());
            conflicts.add(conflict);
        }
        return conflicts;
    }
}
