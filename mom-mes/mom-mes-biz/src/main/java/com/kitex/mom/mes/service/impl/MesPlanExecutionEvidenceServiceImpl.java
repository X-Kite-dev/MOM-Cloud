package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesPlanExecutionEvidenceDTO;
import com.kitex.mom.mes.entity.MesApsBatchAllocationEntity;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesApsBatchAllocationMapper;
import com.kitex.mom.mes.mapper.MesProduceTaskMapper;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import com.kitex.mom.mes.service.MesPlanExecutionEvidenceService;
import com.kitex.mom.mes.service.MesReportSourceAssignmentService;
import com.kitex.mom.mes.dto.MesReportSourceAssignmentDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MPS桶到MES的直接/拆批/合批精确取证。
 *
 * <p>有APS分配时只采用来源分配账，核对整个物理工单分配守恒，绝不把物理合批总量
 * 复制到各个来源桶。缺失工单、数量、工序或时间都留缺口；该查询不修改生产状态。</p>
 */
@Service
@RequiredArgsConstructor
public class MesPlanExecutionEvidenceServiceImpl extends ServiceImpl<MesTaskMapper, MesTaskEntity>
        implements MesPlanExecutionEvidenceService {
    private static final Set<String> STATUSES = Set.of("WAIT", "UN_RECEIVE", "RECEIVE", "PRODUCT", "PAUSE", "FINISH", "CLOSE");
    private static final Set<String> TASK_STATUSES = Set.of("CREATE", "ISSUE", "DISPATCH", "PRODUCT", "FINISH", "CLOSE");
    private static final Set<String> MOVABLE = Set.of("WAIT", "UN_RECEIVE");
    private final MesActualFactoryAccessService factoryAccess;
    private final MesApsBatchAllocationMapper allocationMapper;
    private final MesProduceTaskMapper operationMapper;
    private final MesTaskArrangeMapper arrangeMapper;
    private final MesReportSourceAssignmentService reportSourceService;
    private final MesPlanProductionEventReader productionEventReader;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MesPlanExecutionEvidenceDTO.Evidence query(MesPlanExecutionEvidenceDTO.Query query) {
        String factory = factoryAccess.require("read");
        require(query != null && Objects.equals(factory, query.getFactoryCode()) && query.getPlanNos() != null
                && !query.getPlanNos().isEmpty() && query.getPlanNos().size() <= 500
                && query.getPlanNos().stream().allMatch(no -> StrUtil.isNotBlank(no) && no.length() <= 100)
                && query.getPlanNos().stream().distinct().count() == query.getPlanNos().size(),
                "计划执行取证须指定实际工厂及1至500个不重复精确计划号");
        Long tenant = TenantContextHolder.getTenantId();
        // 使用MES自己的查询起点作为补录边界；结束时刻会把查询期间正常提交误当作旧补录。
        LocalDateTime snapshotStartedAt = LocalDateTime.now();
        List<MesApsBatchAllocationEntity> requested = allocationMapper.selectList(new LambdaQueryWrapper<MesApsBatchAllocationEntity>()
                .eq(MesApsBatchAllocationEntity::getTenantId, tenant).eq(MesApsBatchAllocationEntity::getFactoryCode, factory)
                .in(MesApsBatchAllocationEntity::getIssueNo, query.getPlanNos())
                .orderByAsc(MesApsBatchAllocationEntity::getId).last("LIMIT 5001"));
        within(requested.size(), 5000, "来源分配");
        List<Long> ids = requested.stream().map(MesApsBatchAllocationEntity::getMesTaskId).filter(Objects::nonNull).distinct().toList();
        List<MesTaskEntity> tasks = baseMapper.selectList(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, tenant).eq(MesTaskEntity::getFactoryCode, factory)
                .and(part -> {
                    part.in(MesTaskEntity::getPlanNo, query.getPlanNos());
                    if (!ids.isEmpty()) {
                        part.or().in(MesTaskEntity::getId, ids);
                    }
                }).orderByAsc(MesTaskEntity::getId).last("LIMIT 5001"));
        within(tasks.size(), 5000, "MES工单");
        List<Long> taskIds = tasks.stream().map(MesTaskEntity::getId).toList();
        // 与本次取证处于同一个MES一致性快照，整版批量查询，不逐工单轮询。
        var reportSourceEvidence = reportSourceService.taskEvidenceBatch(taskIds);
        boolean includeEvents = Boolean.TRUE.equals(query.getIncludeProductionEvents());
        var offlineEvidence = includeEvents
                ? productionEventReader.auditEvidence(new ArrayList<>(reportSourceEvidence.values()), factory) : null;
        List<String> codes = tasks.stream().map(MesTaskEntity::getCode).filter(StrUtil::isNotBlank).distinct().toList();
        List<MesApsBatchAllocationEntity> allocations = taskIds.isEmpty() ? List.of()
                : allocationMapper.selectList(new LambdaQueryWrapper<MesApsBatchAllocationEntity>()
                .eq(MesApsBatchAllocationEntity::getTenantId, tenant).eq(MesApsBatchAllocationEntity::getFactoryCode, factory)
                .in(MesApsBatchAllocationEntity::getMesTaskId, taskIds)
                .orderByAsc(MesApsBatchAllocationEntity::getId).last("LIMIT 10001"));
        within(allocations.size(), 10000, "完整物理工单分配");
        List<MesProduceTaskEntity> operations = codes.isEmpty() ? List.of()
                : operationMapper.selectList(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getTenantId, tenant).eq(MesProduceTaskEntity::getFactoryCode, factory)
                .in(MesProduceTaskEntity::getTaskCode, codes).orderByAsc(MesProduceTaskEntity::getId).last("LIMIT 20001"));
        within(operations.size(), 20000, "MES工序");
        List<String> operationCodes = operations.stream().map(MesProduceTaskEntity::getCode).filter(StrUtil::isNotBlank).toList();
        List<MesTaskArrangeEntity> arrangements = operationCodes.isEmpty() ? List.of()
                : arrangeMapper.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, tenant).eq(MesTaskArrangeEntity::getFactoryCode, factory)
                .in(MesTaskArrangeEntity::getProduceTaskCode, operationCodes)
                .orderByAsc(MesTaskArrangeEntity::getId).last("LIMIT 20001"));
        within(arrangements.size(), 20000, "MES派工");
        Map<Long, List<MesApsBatchAllocationEntity>> byTask = allocations.stream()
                .collect(Collectors.groupingBy(MesApsBatchAllocationEntity::getMesTaskId));
        Map<String, List<MesProduceTaskEntity>> operationsByTask = operations.stream()
                .collect(Collectors.groupingBy(MesProduceTaskEntity::getTaskCode));
        Map<String, List<MesTaskArrangeEntity>> arrangementsByOperation = arrangements.stream()
                .collect(Collectors.groupingBy(MesTaskArrangeEntity::getProduceTaskCode));
        Map<String, MesPlanExecutionEvidenceDTO.Plan> plans = new LinkedHashMap<>();
        query.getPlanNos().forEach(no -> plans.put(no, new MesPlanExecutionEvidenceDTO.Plan()
                .setPlanNo(no).setLinks(new ArrayList<>()).setGaps(new ArrayList<>())));
        LocalDateTime observedAt = LocalDateTime.now();
        for (MesTaskEntity task : tasks) {
            List<MesApsBatchAllocationEntity> taskAllocations = byTask.getOrDefault(task.getId(), List.of());
            List<MesProduceTaskEntity> taskOperations = operationsByTask.getOrDefault(task.getCode(), List.of());
            List<MesTaskArrangeEntity> taskArrangements = taskOperations.stream()
                    .flatMap(operation -> arrangementsByOperation.getOrDefault(operation.getCode(), List.of()).stream()).toList();
            List<String> gaps = validateTask(task, taskAllocations, taskOperations);
            var sourceProof = reportSourceEvidence.get(task.getId());
            require(sourceProof != null, "末序报工来源证据未完整返回");
            gaps.addAll(sourceProof.getGaps());
            if (taskArrangements.stream().anyMatch(row -> row.getStatus() == null || !STATUSES.contains(row.getStatus())
                    || row.getCompleteNum() == null || row.getCompleteNum().signum() < 0
                    || row.getStartTime() == null && (positive(row.getCompleteNum())
                    || Set.of("PRODUCT", "PAUSE", "FINISH").contains(StrUtil.blankToDefault(row.getStatus(), "UNKNOWN")))
                    || row.getFinishTime() != null && (row.getStartTime() == null
                    || row.getFinishTime().isBefore(row.getStartTime())))) {
                gaps.add("工单" + task.getCode() + "派工状态或数量不可核验");
            }
            // 在制量来自完整末序报工核定，不读取完工时比例结算字段冒充来源实际报工。
            if (taskAllocations.isEmpty()) {
                MesPlanExecutionEvidenceDTO.Plan plan = plans.get(task.getPlanNo());
                if (plan != null) {
                    plan.getGaps().addAll(gaps);
                    var sourceLink = link(task, null, taskOperations, taskArrangements, sourceProof);
                    if (includeEvents) sourceLink.setProductionSnapshotStartedAt(snapshotStartedAt.toString())
                            .setProductionObservedAt(observedAt.toString());
                    if (includeEvents) productionEventReader.populate(sourceLink, sourceProof, offlineEvidence, observedAt);
                    plan.getLinks().add(sourceLink);
                }
            } else {
                for (MesApsBatchAllocationEntity allocation : taskAllocations) {
                    MesPlanExecutionEvidenceDTO.Plan plan = plans.get(allocation.getIssueNo());
                    if (plan != null) {
                        plan.getGaps().addAll(gaps);
                        var sourceLink = link(task, allocation, taskOperations, taskArrangements, sourceProof);
                        if (includeEvents) sourceLink.setProductionSnapshotStartedAt(snapshotStartedAt.toString())
                                .setProductionObservedAt(observedAt.toString());
                        if (includeEvents) productionEventReader.populate(sourceLink, sourceProof, offlineEvidence, observedAt);
                        plan.getLinks().add(sourceLink);
                    }
                }
                if (plans.containsKey(task.getPlanNo()) && taskAllocations.stream()
                        .noneMatch(row -> Objects.equals(task.getPlanNo(), row.getIssueNo()))) {
                    plans.get(task.getPlanNo()).getGaps().add("工单" + task.getCode() + "直接计划与APS分配来源矛盾");
                }
            }
        }
        for (MesApsBatchAllocationEntity allocation : requested) {
            if (!taskIds.contains(allocation.getMesTaskId())) {
                plans.get(allocation.getIssueNo()).getGaps().add("分配" + allocation.getId() + "缺少当前组织有效工单");
            }
        }
        return new MesPlanExecutionEvidenceDTO.Evidence().setTenantId(tenant).setFactoryCode(factory)
                .setObservedAt(observedAt.toString()).setPlans(new ArrayList<>(plans.values()));
    }

    private List<String> validateTask(MesTaskEntity task, List<MesApsBatchAllocationEntity> allocations,
                                      List<MesProduceTaskEntity> operations) {
        List<String> gaps = new ArrayList<>();
        if (StrUtil.isBlank(task.getCode()) || StrUtil.isBlank(task.getMatCode()) || task.getStatus() == null
                || !TASK_STATUSES.contains(task.getStatus())) {
            gaps.add("工单" + task.getId() + "编码、产品或状态不可核验");
        }
        if (task.getNoticeNum() == null || task.getNoticeNum().signum() <= 0 || task.getCompleteNum() == null
                || task.getCompleteNum().signum() < 0 || task.getCompleteNum().compareTo(task.getNoticeNum()) > 0) {
            gaps.add("工单" + task.getCode() + "计划/当前完成数量缺失或越界");
        }
        if (task.getInStockNum() != null && (task.getInStockNum().signum() < 0 || task.getNoticeNum() != null
                && task.getInStockNum().compareTo(task.getNoticeNum()) > 0)) {
            gaps.add("工单" + task.getCode() + "当前入库量越界");
        }
        if (operations.isEmpty()) {
            gaps.add("工单" + task.getCode() + "缺少实际工序，不能认定未开工或可移动");
        }
        if (StrUtil.isNotBlank(task.getApsBatchNo()) && allocations.isEmpty()) {
            gaps.add("合批工单" + task.getCode() + "缺少完整来源分配账，禁止按直接计划补造归属");
        }
        if (!allocations.isEmpty()) {
            boolean valid = allocations.stream().allMatch(row -> Objects.equals(row.getMesTaskCode(), task.getCode())
                    && row.getIssueId() != null && StrUtil.isNotBlank(row.getIssueNo()) && row.getAllocatedQuantity() != null
                    && row.getAllocatedQuantity().signum() > 0 && row.getCompletedQuantity() != null
                    && row.getCompletedQuantity().signum() >= 0
                    && row.getCompletedQuantity().compareTo(row.getAllocatedQuantity()) <= 0);
            if (!valid || task.getNoticeNum() == null || task.getCompleteNum() == null
                    || valid && (sum(allocations, false).compareTo(task.getNoticeNum()) != 0
                    || Set.of("FINISH", "CLOSE").contains(StrUtil.blankToDefault(task.getStatus(), "UNKNOWN"))
                    && sum(allocations, true).compareTo(task.getCompleteNum()) != 0)) {
                gaps.add("工单" + task.getCode() + "完整来源分配/完成账不守恒或引用不一致");
            }
            if (allocations.stream().anyMatch(row -> row.getInStockQuantity() != null
                    && (row.getInStockQuantity().signum() < 0 || row.getAllocatedQuantity() != null
                    && row.getInStockQuantity().compareTo(row.getAllocatedQuantity()) > 0))
                    || task.getInStockNum() != null && allocations.stream().allMatch(row -> row.getInStockQuantity() != null)
                    && allocations.stream().map(MesApsBatchAllocationEntity::getInStockQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(task.getInStockNum()) != 0) {
                gaps.add("工单" + task.getCode() + "完整来源入库账越界或与物理工单不守恒");
            }
        }
        for (MesProduceTaskEntity operation : operations) {
            if (operation.getStatus() == null || !STATUSES.contains(operation.getStatus())
                    || operation.getCompleteNum() == null || operation.getCompleteNum().signum() < 0
                    || operation.getStartTime() == null && (positive(operation.getCompleteNum())
                    || Set.of("PRODUCT", "PAUSE", "FINISH").contains(StrUtil.blankToDefault(operation.getStatus(), "UNKNOWN")))
                    || operation.getFinishTime() != null && (operation.getStartTime() == null
                    || operation.getFinishTime().isBefore(operation.getStartTime()))) {
                gaps.add("工序" + operation.getCode() + "状态、数量或实际时刻不可核验");
            }
        }
        if (task.getFinishTime() != null && (task.getStartTime() == null || task.getFinishTime().isBefore(task.getStartTime()))) {
            gaps.add("工单" + task.getCode() + "完工早于开工或缺开工时刻");
        }
        if (("FINISH".equals(task.getStatus()) || "CLOSE".equals(task.getStatus())) && task.getFinishTime() == null) {
            gaps.add("工单" + task.getCode() + "终态缺实际完工时刻，不能证明交期兑现");
        }
        return gaps;
    }

    private MesPlanExecutionEvidenceDTO.Link link(MesTaskEntity task, MesApsBatchAllocationEntity allocation,
            List<MesProduceTaskEntity> operations, List<MesTaskArrangeEntity> arrangements,
            MesReportSourceAssignmentDTO.Preview sourceProof) {
        boolean started = task.getStartTime() != null || task.getFinishTime() != null || positive(task.getCompleteNum())
                || positive(task.getInStockNum())
                || Set.of("PRODUCT", "PAUSE", "FINISH", "CLOSE").contains(StrUtil.blankToDefault(task.getStatus(), "UNKNOWN"))
                || operations.stream().anyMatch(row -> row.getStartTime() != null || row.getFinishTime() != null
                || positive(row.getCompleteNum()) || Set.of("PRODUCT", "PAUSE", "FINISH", "CLOSE").contains(
                StrUtil.blankToDefault(row.getStatus(), "UNKNOWN")))
                || arrangements.stream().anyMatch(row -> row.getStartTime() != null || row.getFinishTime() != null
                || positive(row.getCompleteNum()) || Set.of("PRODUCT", "PAUSE", "FINISH", "CLOSE").contains(
                StrUtil.blankToDefault(row.getStatus(), "UNKNOWN")));
        boolean movable = !started && !operations.isEmpty()
                && operations.stream().allMatch(row -> MOVABLE.contains(StrUtil.blankToDefault(row.getStatus(), "UNKNOWN")))
                && arrangements.stream().allMatch(row -> MOVABLE.contains(StrUtil.blankToDefault(row.getStatus(), "UNKNOWN"))
                && row.getStartTime() == null && row.getFinishTime() == null
                && row.getCompleteNum() != null && row.getCompleteNum().signum() == 0);
        LocalDateTime start = operations.stream().map(MesProduceTaskEntity::getStartTime).filter(Objects::nonNull)
                .min(LocalDateTime::compareTo).orElse(task.getStartTime());
        if (task.getStartTime() != null && (start == null || task.getStartTime().isBefore(start))) {
            start = task.getStartTime();
        }
        LocalDateTime arrangeStart = arrangements.stream().map(MesTaskArrangeEntity::getStartTime)
                .filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(null);
        if (arrangeStart != null && (start == null || arrangeStart.isBefore(start))) {
            start = arrangeStart;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("task", Arrays.asList(task.getId(), task.getCode(), task.getMatCode(), task.getPlanNo(),
                task.getApsBatchNo(), task.getStatus(), decimal(task.getNoticeNum()), decimal(task.getCompleteNum()),
                time(task.getStartTime()), time(task.getFinishTime()), decimal(task.getInStockNum())));
        snapshot.put("allocation", allocation == null ? null : Arrays.asList(allocation.getId(), allocation.getIssueId(),
                allocation.getIssueNo(), decimal(allocation.getAllocatedQuantity()), decimal(allocation.getCompletedQuantity()),
                decimal(allocation.getInStockQuantity())));
        snapshot.put("operations", operations.stream().map(row -> Arrays.asList(row.getId(), row.getCode(), row.getRouteCode(),
                row.getRouteOrder(), row.getStatus(), decimal(row.getCompleteNum()), time(row.getStartTime()), time(row.getFinishTime()))).toList());
        snapshot.put("arrangements", arrangements.stream().map(row -> Arrays.asList(row.getId(), row.getCode(), row.getStatus(),
                decimal(row.getCompleteNum()), time(row.getStartTime()), time(row.getFinishTime()))).toList());
        snapshot.put("reportSourceEvidence", sourceProof);
        String completed = allocation == null ? sourceProof.getLiveReportedQuantity() : null;
        if (allocation != null && Boolean.TRUE.equals(sourceProof.getSourceComplete())) {
            var source = sourceProof.getSources().stream()
                    .filter(row -> Objects.equals(row.getAllocationId(), allocation.getId().toString())).toList();
            require(source.size() == 1, "完整报工来源证据与原APS分配行不一致");
            completed = source.get(0).getAssignedQuantity();
        }
        Set<String> pausedOperations = arrangements.stream().filter(row -> "PAUSE".equals(row.getStatus()))
                .map(MesTaskArrangeEntity::getProduceTaskCode).collect(Collectors.toSet());
        return new MesPlanExecutionEvidenceDTO.Link().setTaskId(task.getId()).setTaskCode(task.getCode())
                .setProductCode(task.getMatCode()).setRelationType(allocation == null ? "DIRECT_PLAN" : "APS_BATCH_ALLOCATION")
                .setAllocationId(allocation == null ? null : allocation.getId())
                .setSourceIssueId(allocation == null ? null : allocation.getIssueId())
                .setSourceQuantity(decimal(allocation == null ? task.getNoticeNum() : allocation.getAllocatedQuantity()))
                .setCompletedQuantity(completed).setQuantityBasis(sourceProof.getQuantityBasis())
                .setSettledCompletedQuantity(decimal(task.getCompleteNum()))
                .setInStockQuantity(decimal(allocation == null ? task.getInStockNum() : allocation.getInStockQuantity()))
                .setPhysicalCompletedQuantity(sourceProof.getLiveReportedQuantity())
                .setStatus(task.getStatus()).setActualStartTime(time(start)).setActualFinishTime(time(task.getFinishTime()))
                .setStarted(started).setOperationCount(operations.size())
                .setPausedOperationCount(operations.stream().filter(row -> "PAUSE".equals(row.getStatus())
                        || pausedOperations.contains(row.getCode())).count())
                .setAllOperationsMovable(movable).setExecutionSnapshot(JSONUtil.toJsonStr(snapshot));
    }

    private BigDecimal sum(List<MesApsBatchAllocationEntity> rows, boolean completed) {
        return rows.stream().map(row -> completed ? row.getCompletedQuantity() : row.getAllocatedQuantity())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean positive(BigDecimal quantity) {
        return quantity != null && quantity.signum() > 0;
    }

    private String decimal(BigDecimal quantity) {
        return quantity == null ? null : quantity.stripTrailingZeros().toPlainString();
    }

    private String time(LocalDateTime time) {
        return time == null ? null : time.toString();
    }

    private void within(int count, int limit, String label) {
        require(count <= limit, label + "超过" + limit + "条，禁止截断后形成完整兑现结论");
    }

    private void require(boolean valid, String message) {
        if (!valid) {
            throw new CheckedException(message);
        }
    }
}
