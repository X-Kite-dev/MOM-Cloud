package com.kitex.mom.mes.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.agent.dto.MesPlanTaskScopeDTO;
import com.kitex.mom.mes.entity.MesApsBatchAllocationEntity;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.enums.MesTaskStatusEnum;
import com.kitex.mom.mes.mapper.MesApsBatchAllocationMapper;
import com.kitex.mom.mes.mapper.MesProduceTaskMapper;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 来源计划工单范围查询：包含直接生成工单和APS合批工单，严格执行实际工厂权限。
 * 查询上限只限制展示；超过分配扫描安全边界直接失败，不返回伪完整范围。
 */
@Service
@RequiredArgsConstructor
public class MesPlanTaskScopeQueryService {

    private final MesActualFactoryAccessService factoryAccess;
    private final MesTaskMapper taskMapper;
    private final MesApsBatchAllocationMapper allocationMapper;
    private final MesProduceTaskMapper operationMapper;

    /**
     * 在同一只读快照中冻结计数、工单和来源映射。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MesPlanTaskScopeDTO.Evidence query(MesPlanTaskScopeDTO.Query query) {
        String factory = factoryAccess.require("read");
        if (query == null || !factory.equals(query.getFactoryCode()) || query.getPlanNos() == null
                || query.getPlanNos().isEmpty() || query.getPlanNos().size() > 100
                || query.getPlanNos().stream().anyMatch(no -> !StringUtils.hasText(no) || no.length() > 100)) {
            throw new CheckedException("来源工单查询必须指定实际工厂及1至100个有效计划编号");
        }
        Set<String> plans = new LinkedHashSet<>(query.getPlanNos());
        List<MesApsBatchAllocationEntity> allocations = allocationMapper.selectList(
                new LambdaQueryWrapper<MesApsBatchAllocationEntity>()
                        .eq(MesApsBatchAllocationEntity::getFactoryCode, factory)
                        .in(MesApsBatchAllocationEntity::getIssueNo, plans)
                        .orderByAsc(MesApsBatchAllocationEntity::getId).last("LIMIT 5001"));
        if (allocations.size() > 5000) {
            throw new CheckedException("来源合批分配超过5000条安全边界，请缩小订单范围后调查");
        }
        List<Long> ids = allocations.stream().map(MesApsBatchAllocationEntity::getMesTaskId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        long count = taskMapper.selectCount(scope(factory, plans, ids));
        List<MesTaskEntity> tasks = taskMapper.selectList(scope(factory, plans, ids)
                .orderByAsc(MesTaskEntity::getCode).orderByAsc(MesTaskEntity::getId).last("LIMIT 200"));
        // 核对整张工单分配账，不能仅读取当前订单那一部分后假称整张合批守恒。
        List<Long> taskIds = tasks.stream().map(MesTaskEntity::getId).toList();
        List<MesApsBatchAllocationEntity> allAllocations = taskIds.isEmpty() ? List.of()
                : allocationMapper.selectList(new LambdaQueryWrapper<MesApsBatchAllocationEntity>()
                .eq(MesApsBatchAllocationEntity::getFactoryCode, factory)
                .in(MesApsBatchAllocationEntity::getMesTaskId, taskIds)
                .orderByAsc(MesApsBatchAllocationEntity::getId).last("LIMIT 5001"));
        if (allAllocations.size() > 5000)
            throw new CheckedException("整张合批数量账超过安全边界，不能输出伪完整数量归属");
        List<MesPlanTaskScopeDTO.Link> links = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        for (MesTaskEntity task : tasks) {
            if (!StringUtils.hasText(task.getCode())) {
                gaps.add("MES工单" + task.getId() + "缺少编码，不能开展后续调查");
                continue;
            }
            List<MesApsBatchAllocationEntity> taskAllocations = allAllocations.stream()
                    .filter(row -> java.util.Objects.equals(task.getId(), row.getMesTaskId())).toList();
            if (plans.contains(task.getPlanNo()) && taskAllocations.isEmpty()) {
                MesPlanTaskScopeDTO.Link direct = link(task.getPlanNo(), task.getCode(), "DIRECT_PLAN")
                        .setMaterialCode(task.getMatCode()).setQuantityDataGaps(new ArrayList<>());
                if (task.getApsBatchId() != null || !nonnegative(task.getNoticeNum())
                        || !nonnegative(task.getCompleteNum()) || !nonnegative(task.getInStockNum())) {
                    direct.setQuantityAttributionStatus("DATA_GAP");
                    direct.getQuantityDataGaps().add("直接工单缺少完整数量或APS来源分配账，禁止把总量归属本计划");
                } else {
                    direct.setQuantityAttributionStatus("DIRECT_EXCLUSIVE")
                            .setAllocatedQuantity(task.getNoticeNum()).setCompletedQuantity(task.getCompleteNum())
                            .setInStockQuantity(task.getInStockNum());
                }
                links.add(direct);
            }
            for (MesApsBatchAllocationEntity allocation : allocations) {
                if (!java.util.Objects.equals(task.getId(), allocation.getMesTaskId())) {
                    continue;
                }
                if (!task.getCode().equals(allocation.getMesTaskCode())) {
                    gaps.add("合批分配" + allocation.getId() + "的工单编码与主键不一致，未采用该映射");
                    continue;
                }
                MesPlanTaskScopeDTO.Link source = link(allocation.getIssueNo(), task.getCode(), "APS_BATCH_ALLOCATION")
                        .setMaterialCode(task.getMatCode()).setAllocationId(allocation.getId())
                        .setQuantityDataGaps(new ArrayList<>());
                boolean safe = taskAllocations.stream().allMatch(row -> nonnegative(row.getAllocatedQuantity())
                        && nonnegative(row.getCompletedQuantity()) && nonnegative(row.getInStockQuantity())
                        && task.getCode().equals(row.getMesTaskCode()));
                java.math.BigDecimal totalCompleted = safe ? taskAllocations.stream()
                        .map(MesApsBatchAllocationEntity::getCompletedQuantity)
                        .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add) : null;
                java.math.BigDecimal totalInStock = safe ? taskAllocations.stream()
                        .map(MesApsBatchAllocationEntity::getInStockQuantity)
                        .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add) : null;
                safe = safe && nonnegative(task.getCompleteNum()) && nonnegative(task.getInStockNum())
                        && totalCompleted.compareTo(task.getCompleteNum()) == 0
                        && totalInStock.compareTo(task.getInStockNum()) == 0;
                if (safe) {
                    source.setQuantityAttributionStatus("ALLOCATED").setAllocatedQuantity(allocation.getAllocatedQuantity())
                            .setCompletedQuantity(allocation.getCompletedQuantity()).setInStockQuantity(allocation.getInStockQuantity());
                } else {
                    source.setQuantityAttributionStatus("DATA_GAP");
                    source.getQuantityDataGaps().add("合批来源数量账与工单总账不守恒或缺失，须先对账；不按比例猜测完工归属");
                }
                links.add(source);
            }
        }
        // 分配账指向不存在/逻辑删除工单时，不能把丢失的关联误判为尚未开工。
        if (!ids.isEmpty()) {
            long resolved = taskMapper.selectCount(new LambdaQueryWrapper<MesTaskEntity>()
                    .eq(MesTaskEntity::getFactoryCode, factory).in(MesTaskEntity::getId, ids));
            if (resolved != ids.size()) {
                gaps.add("部分APS合批分配缺少当前工厂有效工单，须治理来源关联");
            }
        }
        if (count > tasks.size()) {
            gaps.add("MES来源工单共" + count + "条，仅返回前200条，不得形成整单完整结论");
        }
        LocalDateTime observedAt = LocalDateTime.now();
        Map<String, Map<String, Object>> operations = operationCounts(factory, tasks);
        List<MesPlanTaskScopeDTO.Task> rows = tasks.stream()
                .map(task -> riskTask(task, operations.get(task.getCode()), observedAt)).toList();
        return new MesPlanTaskScopeDTO.Evidence().setFactoryCode(factory).setMatchedTaskCount(count)
                .setTasks(rows).setLinks(links).setDataGaps(gaps).setTruncated(count > tasks.size());
    }

    /**
     * 一次分组查询取得全部返回工单的工序统计，避免逐工单远程查询；租户拦截仍生效。
     */
    private Map<String, Map<String, Object>> operationCounts(String factory, List<MesTaskEntity> tasks) {
        List<String> codes = tasks.stream().map(MesTaskEntity::getCode)
                .filter(StringUtils::hasText).distinct().toList();
        Map<String, Map<String, Object>> result = new HashMap<>();
        if (codes.isEmpty()) {
            return result;
        }
        operationMapper.selectMaps(new QueryWrapper<MesProduceTaskEntity>()
                        .select("task_code", "COUNT(*) AS operation_count",
                                "SUM(CASE WHEN status='PAUSE' THEN 1 ELSE 0 END) AS paused_count",
                                "SUM(CASE WHEN status IN ('WAIT','UN_RECEIVE','RECEIVE','PRODUCT','PAUSE','CLOSE','FINISH')"
                                        + " THEN 0 ELSE 1 END) AS unknown_status_count")
                        .eq("factory_code", factory).in("task_code", codes).groupBy("task_code"))
                .forEach(row -> result.put(String.valueOf(row.get("task_code")), row));
        return result;
    }

    /**
     * 保留未知事实，不将未维护日期/数量、无工序或关闭但无完工时间解释为正常。
     */
    private MesPlanTaskScopeDTO.Task riskTask(MesTaskEntity task, Map<String, Object> operations,
                                              LocalDateTime observedAt) {
        List<String> gaps = new ArrayList<>();
        Boolean delayed = null;
        boolean knownStatus = java.util.Arrays.stream(MesTaskStatusEnum.values())
                .anyMatch(status -> status.name().equals(task.getStatus()));
        if (!knownStatus || task.getPlanEndDate() == null) {
            gaps.add("工单状态或计划截止时间不足，不能判定逾期");
        } else if (task.getFinishTime() != null) {
            delayed = task.getFinishTime().isAfter(task.getPlanEndDate());
        } else if (Set.of("FINISH", "CLOSE").contains(task.getStatus())) {
            gaps.add("完成或关闭工单缺少实际完工时间");
        } else {
            delayed = task.getPlanEndDate().isBefore(observedAt);
        }
        Long paused = operations == null ? null : ((Number) operations.get("paused_count")).longValue();
        if (paused == null) {
            gaps.add("未取得有效工序任务，不能判定暂停数");
        } else if (((Number) operations.get("unknown_status_count")).longValue() > 0) {
            gaps.add("存在未识别工序状态，已知暂停数不代表所有工序正常");
        }
        java.math.BigDecimal unqualified = task.getUnPassNum();
        if (unqualified == null || unqualified.signum() < 0) {
            unqualified = null;
            gaps.add("不合格数量未维护或非法，不能按零解释");
        }
        return new MesPlanTaskScopeDTO.Task().setTaskId(task.getId()).setTaskCode(task.getCode())
                .setMatCode(task.getMatCode()).setStatus(task.getStatus()).setRiskObservedAt(observedAt)
                .setPlanEndTime(task.getPlanEndDate()).setActualFinishTime(task.getFinishTime())
                .setDelayed(delayed).setPausedOperationCount(paused).setUnqualifiedQuantity(unqualified)
                .setRiskDataGaps(gaps);
    }

    private LambdaQueryWrapper<MesTaskEntity> scope(String factory, Set<String> plans, List<Long> ids) {
        return new LambdaQueryWrapper<MesTaskEntity>().eq(MesTaskEntity::getFactoryCode, factory)
                .and(where -> {
                    where.in(MesTaskEntity::getPlanNo, plans);
                    if (!ids.isEmpty()) {
                        where.or().in(MesTaskEntity::getId, ids);
                    }
                });
    }

    private MesPlanTaskScopeDTO.Link link(String planNo, String code, String type) {
        return new MesPlanTaskScopeDTO.Link().setPlanNo(planNo).setTaskCode(code).setRelationType(type);
    }

    private boolean nonnegative(java.math.BigDecimal quantity) {
        return quantity != null && quantity.signum() >= 0;
    }
}
