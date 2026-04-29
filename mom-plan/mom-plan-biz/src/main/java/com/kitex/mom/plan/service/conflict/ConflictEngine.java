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
        result.addAll(detectSameProductSameDayConcentration(taskId, taskDetails));
        result.addAll(detectMissingProcessRoute(taskId, taskDetails));
        return result;
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
