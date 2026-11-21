package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesApsLocalScheduleDTO;
import com.kitex.mom.mes.dto.MesApsOperationSnapshotDTO;
import com.kitex.mom.mes.dto.MesApsPublicationProbeDTO;
import com.kitex.mom.mes.entity.MesApsScheduleSyncEntity;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesApsScheduleSyncMapper;
import com.kitex.mom.mes.mapper.MesProduceTaskMapper;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import com.kitex.mom.mes.service.MesApsScheduleSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MesApsScheduleSyncServiceImpl implements MesApsScheduleSyncService {
    private static final Set<String> MOVABLE_STATUSES = Set.of("WAIT", "UN_RECEIVE");

    private final MesTaskMapper taskMapper;
    private final MesProduceTaskMapper produceTaskMapper;
    private final MesTaskArrangeMapper arrangeMapper;
    private final MesApsScheduleSyncMapper syncMapper;

    @Override
    public MesApsLocalScheduleDTO.StateBundle operationStates(String planNo, String factoryCode) {
        requireText(planNo, "生产计划号不能为空");
        requireText(factoryCode, "工厂编码不能为空");
        return toBundle(loadFacts(planNo.trim(), factoryCode.trim(), false));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesApsLocalScheduleDTO.ApplyReceipt apply(MesApsLocalScheduleDTO.ApplyCommand command) {
        require(command != null, "MES局部重排同步命令不能为空");
        requireText(command.getRequestId(), "同步幂等号不能为空");
        requireText(command.getPlanNo(), "生产计划号不能为空");
        requireText(command.getFactoryCode(), "工厂编码不能为空");
        requireText(command.getExpectedExecutionFingerprint(), "MES执行状态指纹不能为空");
        require(command.getReplanCaseId() != null, "局部重排方案ID不能为空");
        require(command.getOperations() != null && !command.getOperations().isEmpty(), "局部重排工序不能为空");
        String snapshot = JSONUtil.toJsonStr(command);
        syncMapper.lockCase(MesApsPublicationProbeServiceImpl.caseKey(command.getFactoryCode().trim(), command.getReplanCaseId()));
        syncMapper.lockRequest(command.getRequestId().trim());
        MesApsScheduleSyncEntity duplicate = syncMapper.selectOne(new LambdaQueryWrapper<MesApsScheduleSyncEntity>()
                .eq(MesApsScheduleSyncEntity::getRequestId, command.getRequestId().trim()));
        if (duplicate != null) {
            require(Objects.equals(duplicate.getRequestSnapshot(), snapshot), "MES同步幂等号已被不同排程使用");
            require("SUCCESS".equals(duplicate.getSyncStatus()), "MES同步幂等记录状态异常");
            return receipt(duplicate);
        }

        Set<String> updateKeys = new LinkedHashSet<>();
        boolean nodeUpdates = command.getOperations().stream()
                .anyMatch(row -> row != null && StrUtil.isNotBlank(row.getSourceRouteNodeCode()));
        for (MesApsLocalScheduleDTO.OperationUpdate update : command.getOperations()) {
            require(update != null && StrUtil.isNotBlank(update.getRouteCode()), "局部重排工序编码不能为空");
            require(nodeUpdates ? validNode(update.getSourceRouteNodeCode()) : update.getSourceRouteNodeCode() == null,
                    "同步节点身份不完整，不能混用新旧合同");
            require(updateKeys.add(nodeUpdates ? update.getSourceRouteNodeCode() : update.getRouteCode()),
                    "局部重排节点不能重复；旧合同主工序必须唯一");
            require(update.getPlannedStartTime() != null && update.getPlannedEndTime() != null
                    && update.getPlannedEndTime().isAfter(update.getPlannedStartTime()), "局部重排工序时间无效");
            requireText(update.getResourceCode(), "局部重排工序必须具有确定资源");
            requireText(update.getApsOperationSnapshot(), "局部重排工序必须携带APS证据快照");
        }

        Facts facts = loadFacts(command.getPlanNo().trim(), command.getFactoryCode().trim(), true);
        MesApsLocalScheduleDTO.StateBundle before = toBundle(facts);
        require(Objects.equals(command.getExpectedExecutionFingerprint(), before.getExecutionFingerprint()),
                "MES工序执行或派工事实已变化，局部重排审批失效");
        Map<Long, MesApsLocalScheduleDTO.OperationUpdate> updates = new LinkedHashMap<>();
        if (!facts.tasks.isEmpty()) {
            for (var update : command.getOperations()) {
                MesProduceTaskEntity target = resolveUpdate(facts, update);
                require(updates.putIfAbsent(target.getId(), update) == null, "同一实际MES工序被重复指定");
            }
        }

        Map<String, List<MesTaskArrangeEntity>> arrangeByProduceCode = facts.arrangements.stream()
                .filter(item -> StrUtil.isNotBlank(item.getProduceTaskCode()))
                .collect(Collectors.groupingBy(MesTaskArrangeEntity::getProduceTaskCode));
        int changed = 0;
        for (MesProduceTaskEntity produce : facts.produceTasks) {
            MesApsLocalScheduleDTO.OperationUpdate update = updates.get(produce.getId());
            if (update == null) continue;
            String block = blockedReason(produce, arrangeByProduceCode.getOrDefault(produce.getCode(), List.of()));
            require(block == null, "MES工序[" + produce.getRouteCode() + "]" + block);
            apply(produce, update);
            // 显式写计划字段，包含null清空；默认updateById跳过null会留下旧设备/人员/工装。
            require(updatePlannedFields(produce), "MES生产工序计划更新失败");
            for (MesTaskArrangeEntity arrange : arrangeByProduceCode.getOrDefault(produce.getCode(), List.of())) {
                apply(arrange, update);
                require(updatePlannedFields(arrange), "MES派工工序计划更新失败");
            }
            changed++;
        }

        for (MesTaskEntity task : facts.tasks) {
            List<MesProduceTaskEntity> taskOperations = facts.produceTasks.stream()
                    .filter(item -> Objects.equals(task.getCode(), item.getTaskCode())).toList();
            LocalDateTime start = taskOperations.stream().map(MesProduceTaskEntity::getPlannedStartTime)
                    .filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(task.getPlanStartDate());
            LocalDateTime end = taskOperations.stream().map(MesProduceTaskEntity::getPlannedEndTime)
                    .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(task.getPlanEndDate());
            task.setPlanStartDate(start).setPlanEndDate(end);
            require(taskMapper.updateById(task) == 1, "MES生产工单计划日期更新失败");
        }

        Facts afterFacts = loadFacts(command.getPlanNo().trim(), command.getFactoryCode().trim(), false);
        MesApsLocalScheduleDTO.StateBundle after = toBundle(afterFacts);
        MesApsScheduleSyncEntity log = new MesApsScheduleSyncEntity()
                .setRequestId(command.getRequestId().trim()).setRequestSnapshot(snapshot)
                .setPlanNo(command.getPlanNo().trim()).setReplanCaseId(command.getReplanCaseId())
                .setBeforeExecutionFingerprint(before.getExecutionFingerprint())
                .setAfterExecutionFingerprint(after.getExecutionFingerprint()).setUpdatedOperationCount(changed)
                .setBeforeScheduleSnapshot(JSONUtil.toJsonStr(before.getOperations()))
                .setAfterScheduleSnapshot(JSONUtil.toJsonStr(after.getOperations()))
                .setSyncStatus("SUCCESS").setAppliedTime(LocalDateTime.now())
                .setFactoryCode(command.getFactoryCode().trim());
        require(syncMapper.insert(log) == 1, "MES局部重排同步履历保存失败");
        return receipt(log);
    }

    private Facts loadFacts(String planNo, String factoryCode, boolean lock) {
        LambdaQueryWrapper<MesTaskEntity> taskQuery = new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getPlanNo, planNo).eq(MesTaskEntity::getFactoryCode, factoryCode)
                .orderByAsc(MesTaskEntity::getId);
        if (lock) taskQuery.last("FOR UPDATE");
        List<MesTaskEntity> tasks = taskMapper.selectList(taskQuery);
        List<String> taskCodes = tasks.stream().map(MesTaskEntity::getCode).filter(StrUtil::isNotBlank).toList();
        if (taskCodes.isEmpty()) return new Facts(planNo, factoryCode, tasks, List.of(), List.of());
        LambdaQueryWrapper<MesProduceTaskEntity> produceQuery = new LambdaQueryWrapper<MesProduceTaskEntity>()
                .in(MesProduceTaskEntity::getTaskCode, taskCodes).eq(MesProduceTaskEntity::getFactoryCode, factoryCode)
                .orderByAsc(MesProduceTaskEntity::getTaskCode).orderByAsc(MesProduceTaskEntity::getRouteOrder)
                .orderByAsc(MesProduceTaskEntity::getId);
        if (lock) produceQuery.last("FOR UPDATE");
        List<MesProduceTaskEntity> produce = produceTaskMapper.selectList(produceQuery);
        List<String> produceCodes = produce.stream().map(MesProduceTaskEntity::getCode).filter(StrUtil::isNotBlank).toList();
        if (produceCodes.isEmpty()) return new Facts(planNo, factoryCode, tasks, produce, List.of());
        LambdaQueryWrapper<MesTaskArrangeEntity> arrangeQuery = new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .in(MesTaskArrangeEntity::getProduceTaskCode, produceCodes)
                .eq(MesTaskArrangeEntity::getFactoryCode, factoryCode)
                .orderByAsc(MesTaskArrangeEntity::getProduceTaskCode).orderByAsc(MesTaskArrangeEntity::getId);
        if (lock) arrangeQuery.last("FOR UPDATE");
        return new Facts(planNo, factoryCode, tasks, produce, arrangeMapper.selectList(arrangeQuery));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public MesApsPublicationProbeDTO.CurrentSchedule verifyAppliedSchedule(
            MesApsLocalScheduleDTO.ApplyCommand command, List<String> expectedProcessCodes) {
        return verifyAppliedSchedule(command, expectedProcessCodes, null);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public MesApsPublicationProbeDTO.CurrentSchedule verifyAppliedSchedule(
            MesApsLocalScheduleDTO.ApplyCommand command, List<String> expectedProcessCodes,
            List<String> expectedSourceRouteNodeCodes) {
        Facts facts = loadFacts(command.getPlanNo(), command.getFactoryCode(), false);
        List<String> gaps = new ArrayList<>();
        boolean nodeScope = expectedSourceRouteNodeCodes != null;
        List<String> expectedKeys = nodeScope ? expectedSourceRouteNodeCodes : expectedProcessCodes;
        require(expectedProcessCodes != null && expectedKeys != null && !expectedKeys.isEmpty()
                && expectedKeys.size() == expectedProcessCodes.size()
                && new HashSet<>(expectedKeys).size() == expectedKeys.size(), "原根计划完整范围不合法");
        Map<String, MesProduceTaskEntity> byCode = new HashMap<>();
        for (MesProduceTaskEntity row : facts.produceTasks) {
            String key = nodeScope ? row.getSourceRouteNodeCode() : row.getRouteCode();
            if (StrUtil.isBlank(key) || byCode.putIfAbsent(key, row) != null) {
                gaps.add("MES工序缺编码或存在多物理工单映射，不能取首条恢复");
            }
        }
        if (facts.tasks.size() != 1 || facts.produceTasks.size() > 1000
                || !byCode.keySet().equals(new HashSet<>(expectedKeys))) {
            gaps.add("当前MES完整工序范围与原根计划不一致，不能缩小恢复范围");
        }
        for (int index = 0; index < expectedKeys.size(); index++) {
            MesProduceTaskEntity row = byCode.get(expectedKeys.get(index));
            if (row != null && !Objects.equals(row.getRouteCode(), expectedProcessCodes.get(index))) {
                gaps.add("原节点与主工序对应关系已变化：" + expectedKeys.get(index));
            }
        }
        require(command.getOperations() != null && !command.getOperations().isEmpty(), "原应用目标工序不能为空");
        Set<Long> targets = new HashSet<>();
        for (var update : command.getOperations()) {
            require(update != null && StrUtil.isNotBlank(update.getRouteCode()), "原应用工序不能为空");
            MesProduceTaskEntity row;
            try {
                row = resolveUpdate(facts, update);
                require(targets.add(row.getId()), "原应用重复指定同一实际MES工序");
            } catch (CheckedException ex) {
                gaps.add(update.getRouteCode() + "：" + ex.getMessage());
                continue;
            }
            MesProduceTaskEntity copy = new MesProduceTaskEntity();
            BeanUtils.copyProperties(row, copy);
            apply(copy, update);
            if (!Objects.equals(row.getPlannedStartTime(), update.getPlannedStartTime())
                    || !Objects.equals(row.getPlannedEndTime(), update.getPlannedEndTime())
                    || !Objects.equals(JSONUtil.toJsonStr(row), JSONUtil.toJsonStr(copy))) {
                gaps.add(update.getRouteCode() + "：工序计划时刻、资源或冻结APS证据已变化");
            }
            for (MesTaskArrangeEntity arrange : facts.arrangements) {
                if (!Objects.equals(arrange.getProduceTaskCode(), row.getCode())) {
                    continue;
                }
                MesTaskArrangeEntity arrangedCopy = new MesTaskArrangeEntity();
                BeanUtils.copyProperties(arrange, arrangedCopy);
                apply(arrangedCopy, update);
                if (!Objects.equals(arrange.getPlannedStartTime(), update.getPlannedStartTime())
                        || !Objects.equals(arrange.getPlannedEndTime(), update.getPlannedEndTime())
                        || !Objects.equals(JSONUtil.toJsonStr(arrange), JSONUtil.toJsonStr(arrangedCopy))) {
                    gaps.add(update.getRouteCode() + "：派工计划属性与原应用决定不一致");
                }
            }
        }
        for (MesTaskEntity task : facts.tasks) {
            List<MesProduceTaskEntity> rows = facts.produceTasks.stream()
                    .filter(row -> Objects.equals(row.getTaskCode(), task.getCode())).toList();
            LocalDateTime start = rows.stream().map(MesProduceTaskEntity::getPlannedStartTime)
                    .filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(null);
            LocalDateTime end = rows.stream().map(MesProduceTaskEntity::getPlannedEndTime)
                    .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
            if (start == null || end == null || !Objects.equals(start, task.getPlanStartDate())
                    || !Objects.equals(end, task.getPlanEndDate())) {
                gaps.add(task.getCode() + "：MES工单计划日期摘要与完整工序不一致");
            }
        }
        // 不比较接收/开工/报工是否仍为零：这里只核对已应用计划，绝不反向移动现场事实。
        return new MesApsPublicationProbeDTO.CurrentSchedule().setMatches(gaps.isEmpty())
                .setFacts(toBundle(facts)).setGaps(gaps)
                .setPlannedQuantity(facts.tasks.size() == 1 ? facts.tasks.get(0).getPlannedNum() : null)
                .setOperationQuantities(byCode.entrySet().stream().filter(entry -> entry.getValue().getPlannedNum() != null)
                        .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getPlannedNum())));
    }

    /**
     * 仅修改计划属性；实际状态、时间、数量和物料/质量事实均不写。
     */
    private boolean updatePlannedFields(MesProduceTaskEntity row) {
        return produceTaskMapper.update(null, new LambdaUpdateWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getId, row.getId()).eq(MesProduceTaskEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesProduceTaskEntity::getFactoryCode, row.getFactoryCode())
                .set(MesProduceTaskEntity::getPlannedStartTime, row.getPlannedStartTime())
                .set(MesProduceTaskEntity::getPlannedEndTime, row.getPlannedEndTime())
                .set(MesProduceTaskEntity::getPlannedWorksiteCode, row.getPlannedWorksiteCode())
                .set(MesProduceTaskEntity::getPlannedWorksiteName, row.getPlannedWorksiteName())
                .set(MesProduceTaskEntity::getPlannedResourceSlotNo, row.getPlannedResourceSlotNo())
                .set(MesProduceTaskEntity::getPlannedResourceGroupCode, row.getPlannedResourceGroupCode())
                .set(MesProduceTaskEntity::getPlannedResourceGroupName, row.getPlannedResourceGroupName())
                .set(MesProduceTaskEntity::getPlannedResourceGroupCapacity, row.getPlannedResourceGroupCapacity())
                .set(MesProduceTaskEntity::getPlannedResourceGroupSlotNo, row.getPlannedResourceGroupSlotNo())
                .set(MesProduceTaskEntity::getPlannedDeviceCode, row.getPlannedDeviceCode())
                .set(MesProduceTaskEntity::getPlannedDeviceName, row.getPlannedDeviceName())
                .set(MesProduceTaskEntity::getPlannedWorkerCode, row.getPlannedWorkerCode())
                .set(MesProduceTaskEntity::getPlannedWorkerName, row.getPlannedWorkerName())
                .set(MesProduceTaskEntity::getPlannedToolingCodes, row.getPlannedToolingCodes())
                .set(MesProduceTaskEntity::getPlannedToolingNames, row.getPlannedToolingNames())
                .set(MesProduceTaskEntity::getToolingAllocationSnapshot, row.getToolingAllocationSnapshot())
                .set(MesProduceTaskEntity::getToolingLifeUsageSnapshot, row.getToolingLifeUsageSnapshot())
                .set(MesProduceTaskEntity::getApsOperationSnapshot, row.getApsOperationSnapshot())
                .set(MesProduceTaskEntity::getUpdateBy, SecurityUtils.getUser().getUsername())
                .set(MesProduceTaskEntity::getUpdateTime, LocalDateTime.now())) == 1;
    }

    /**
     * 派工同样显式清空旧计划资源，不覆盖实际派工设备/人员或执行数量。
     */
    private boolean updatePlannedFields(MesTaskArrangeEntity row) {
        return arrangeMapper.update(null, new LambdaUpdateWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getId, row.getId()).eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, row.getFactoryCode())
                .set(MesTaskArrangeEntity::getPlannedStartTime, row.getPlannedStartTime())
                .set(MesTaskArrangeEntity::getPlannedEndTime, row.getPlannedEndTime())
                .set(MesTaskArrangeEntity::getPlannedWorksiteCode, row.getPlannedWorksiteCode())
                .set(MesTaskArrangeEntity::getPlannedWorksiteName, row.getPlannedWorksiteName())
                .set(MesTaskArrangeEntity::getPlannedResourceSlotNo, row.getPlannedResourceSlotNo())
                .set(MesTaskArrangeEntity::getPlannedResourceGroupCode, row.getPlannedResourceGroupCode())
                .set(MesTaskArrangeEntity::getPlannedResourceGroupName, row.getPlannedResourceGroupName())
                .set(MesTaskArrangeEntity::getPlannedResourceGroupCapacity, row.getPlannedResourceGroupCapacity())
                .set(MesTaskArrangeEntity::getPlannedResourceGroupSlotNo, row.getPlannedResourceGroupSlotNo())
                .set(MesTaskArrangeEntity::getPlannedDeviceCode, row.getPlannedDeviceCode())
                .set(MesTaskArrangeEntity::getPlannedDeviceName, row.getPlannedDeviceName())
                .set(MesTaskArrangeEntity::getPlannedWorkerCode, row.getPlannedWorkerCode())
                .set(MesTaskArrangeEntity::getPlannedWorkerName, row.getPlannedWorkerName())
                .set(MesTaskArrangeEntity::getPlannedToolingCodes, row.getPlannedToolingCodes())
                .set(MesTaskArrangeEntity::getPlannedToolingNames, row.getPlannedToolingNames())
                .set(MesTaskArrangeEntity::getToolingAllocationSnapshot, row.getToolingAllocationSnapshot())
                .set(MesTaskArrangeEntity::getToolingLifeUsageSnapshot, row.getToolingLifeUsageSnapshot())
                .set(MesTaskArrangeEntity::getApsOperationSnapshot, row.getApsOperationSnapshot())
                .set(MesTaskArrangeEntity::getUpdateBy, SecurityUtils.getUser().getUsername())
                .set(MesTaskArrangeEntity::getUpdateTime, LocalDateTime.now())) == 1;
    }

    private MesApsLocalScheduleDTO.StateBundle toBundle(Facts facts) {
        return new MesApsLocalScheduleDTO.StateBundle().setPlanNo(facts.planNo).setFactoryCode(facts.factoryCode)
                .setMesTaskPresent(!facts.tasks.isEmpty()).setOperations(operationStates(facts.produceTasks, facts.arrangements))
                .setExecutionFingerprint(fingerprint(facts));
    }

    /** 局部重排和正式回评共用原工序与派工状态，两份输入都由调用方限定到实际工单。 */
    static List<MesApsLocalScheduleDTO.OperationState> operationStates(List<MesProduceTaskEntity> operations,
                                                                      List<MesTaskArrangeEntity> arrangements) {
        Map<String, List<MesTaskArrangeEntity>> byProduce = arrangements.stream()
                .filter(item -> StrUtil.isNotBlank(item.getProduceTaskCode()))
                .collect(Collectors.groupingBy(MesTaskArrangeEntity::getProduceTaskCode));
        return operations.stream().map(item -> {
            String reason = blockedReason(item, byProduce.getOrDefault(item.getCode(), List.of()));
            return new MesApsLocalScheduleDTO.OperationState().setProduceTaskId(item.getId())
                    .setProduceTaskCode(item.getCode()).setTaskCode(item.getTaskCode())
                    .setSourceRouteNodeCode(item.getSourceRouteNodeCode())
                    .setRouteCode(item.getRouteCode()).setRouteName(item.getRouteName()).setRouteOrder(item.getRouteOrder())
                    .setStatus(item.getStatus()).setActualStartTime(item.getStartTime()).setActualFinishTime(item.getFinishTime())
                    .setActualStartTimeText(item.getStartTime() == null ? null : item.getStartTime().toString())
                    .setActualFinishTimeText(item.getFinishTime() == null ? null : item.getFinishTime().toString())
                    .setCompleteQuantity(item.getCompleteNum()).setPlannedStartTime(item.getPlannedStartTime())
                    .setPlannedEndTime(item.getPlannedEndTime()).setMovable(reason == null).setBlockedReason(reason);
        }).toList();
    }

    private static String blockedReason(MesProduceTaskEntity produce, List<MesTaskArrangeEntity> arrangements) {
        if (produce.getStatus() == null || !MOVABLE_STATUSES.contains(produce.getStatus())) {
            return "状态为" + produce.getStatus() + "，已进入执行链路，禁止重排";
        }
        if (produce.getStartTime() != null || produce.getFinishTime() != null || positive(produce.getCompleteNum())) {
            return "已有实际开完工或报工数量，禁止重排";
        }
        for (MesTaskArrangeEntity item : arrangements) {
            if (item.getStatus() == null || !MOVABLE_STATUSES.contains(item.getStatus()) || item.getStartTime() != null
                    || item.getFinishTime() != null || positive(item.getCompleteNum())) {
                return "已有派工被接收、开工或报工，禁止重排";
            }
        }
        return null;
    }

    /**
     * 精确解析真实任务，再核原节点和新的APS证据；不把同工序的所有物理工单一并移动。
     */
    private MesProduceTaskEntity resolveUpdate(Facts facts, MesApsLocalScheduleDTO.OperationUpdate update) {
        boolean nodeMode = StrUtil.isNotBlank(update.getSourceRouteNodeCode());
        require(nodeMode ? validNode(update.getSourceRouteNodeCode()) : update.getSourceRouteNodeCode() == null,
                "原同步节点格式不合法");
        List<MesProduceTaskEntity> matches = facts.produceTasks.stream().filter(row -> nodeMode
                ? Objects.equals(row.getSourceRouteNodeCode(), update.getSourceRouteNodeCode())
                : Objects.equals(row.getRouteCode(), update.getRouteCode())).toList();
        require(matches.size() == 1, "同步目标未唯一对应实际MES工序，禁止按同码批量覆盖");
        MesProduceTaskEntity target = matches.get(0);
        require(Objects.equals(target.getRouteCode(), update.getRouteCode())
                        && (update.getProduceTaskId() == null || Objects.equals(target.getId(), update.getProduceTaskId()))
                        && (update.getProduceTaskCode() == null || Objects.equals(target.getCode(), update.getProduceTaskCode())),
                "同步节点、主工序与实际任务身份不一致");
        if (nodeMode) {
            MesApsOperationSnapshotDTO next = snapshot(update.getApsOperationSnapshot());
            MesApsOperationSnapshotDTO original = snapshot(target.getApsOperationSnapshot());
            MesTaskEntity task = facts.tasks.stream().filter(row -> Objects.equals(row.getCode(), target.getTaskCode()))
                    .findFirst().orElseThrow(() -> new CheckedException("实际工序缺少原MES工单"));
            require(Objects.equals(next.getSourceRouteNodeCode(), update.getSourceRouteNodeCode())
                            && Objects.equals(original.getSourceRouteNodeCode(), update.getSourceRouteNodeCode())
                            && Objects.equals(next.getProcessCode(), target.getRouteCode())
                            && Objects.equals(next.getRouteCode(), task.getProcessRouteCode())
                            && Objects.equals(next.getRouteWorkingId(), original.getRouteWorkingId())
                            && Objects.equals(next.getSourceRouteNodeCode(), "RN-" + next.getRouteWorkingId())
                            && next.getPredecessorNodeCodes() != null && original.getPredecessorNodeCodes() != null
                            && new HashSet<>(Arrays.asList(next.getPredecessorNodeCodes().split(",")))
                            .equals(new HashSet<>(Arrays.asList(original.getPredecessorNodeCodes().split(",")))),
                    "局部重排不能改换原节点、路线或前序图，须核对工程变更");
        }
        return target;
    }

    private MesApsOperationSnapshotDTO snapshot(String raw) {
        require(StrUtil.isNotBlank(raw), "节点同步缺少原APS冻结证据");
        try {
            MesApsOperationSnapshotDTO value = JSONUtil.toBean(raw, MesApsOperationSnapshotDTO.class);
            require(value != null, "APS冻结证据不能为空");
            return value;
        } catch (Exception ex) {
            throw new CheckedException("APS节点冻结证据无法解析，请核对原单");
        }
    }

    private boolean validNode(String node) {
        return node != null && node.matches("RN-[1-9][0-9]*");
    }

    private void apply(MesProduceTaskEntity target, MesApsLocalScheduleDTO.OperationUpdate source) {
        target.setPlannedStartTime(source.getPlannedStartTime()).setPlannedEndTime(source.getPlannedEndTime())
                .setPlannedWorksiteCode(source.getResourceCode()).setPlannedWorksiteName(source.getResourceName())
                .setPlannedResourceSlotNo(source.getResourceSlotNo()).setPlannedResourceGroupCode(source.getResourceGroupCode())
                .setPlannedResourceGroupName(source.getResourceGroupName()).setPlannedResourceGroupCapacity(source.getResourceGroupCapacity())
                .setPlannedResourceGroupSlotNo(source.getResourceGroupSlotNo()).setPlannedDeviceCode(source.getDeviceCode())
                .setPlannedDeviceName(source.getDeviceName()).setPlannedWorkerCode(source.getEmployeeCode())
                .setPlannedWorkerName(source.getEmployeeName()).setPlannedToolingCodes(source.getToolingCodes())
                .setPlannedToolingNames(source.getToolingNames()).setToolingAllocationSnapshot(source.getToolingAllocationSnapshot())
                .setToolingLifeUsageSnapshot(source.getToolingLifeUsageSnapshot()).setApsOperationSnapshot(source.getApsOperationSnapshot());
    }

    private void apply(MesTaskArrangeEntity target, MesApsLocalScheduleDTO.OperationUpdate source) {
        target.setPlannedStartTime(source.getPlannedStartTime()).setPlannedEndTime(source.getPlannedEndTime())
                .setPlannedWorksiteCode(source.getResourceCode()).setPlannedWorksiteName(source.getResourceName())
                .setPlannedResourceSlotNo(source.getResourceSlotNo()).setPlannedResourceGroupCode(source.getResourceGroupCode())
                .setPlannedResourceGroupName(source.getResourceGroupName()).setPlannedResourceGroupCapacity(source.getResourceGroupCapacity())
                .setPlannedResourceGroupSlotNo(source.getResourceGroupSlotNo()).setPlannedDeviceCode(source.getDeviceCode())
                .setPlannedDeviceName(source.getDeviceName()).setPlannedWorkerCode(source.getEmployeeCode())
                .setPlannedWorkerName(source.getEmployeeName()).setPlannedToolingCodes(source.getToolingCodes())
                .setPlannedToolingNames(source.getToolingNames()).setToolingAllocationSnapshot(source.getToolingAllocationSnapshot())
                .setToolingLifeUsageSnapshot(source.getToolingLifeUsageSnapshot()).setApsOperationSnapshot(source.getApsOperationSnapshot());
    }

    private String fingerprint(Facts facts) {
        StringBuilder raw = new StringBuilder(facts.factoryCode).append('|').append(facts.planNo);
        facts.tasks.forEach(item -> raw.append("|T:").append(item.getId()).append(':').append(item.getStatus())
                .append(':').append(item.getStartTime()).append(':').append(item.getFinishTime()));
        facts.produceTasks.forEach(item -> raw.append("|P:").append(item.getId()).append(':').append(item.getCode())
                .append(':').append(item.getRouteCode()).append(':').append(item.getStatus()).append(':')
                .append(item.getStartTime()).append(':').append(item.getFinishTime()).append(':').append(item.getCompleteNum())
                .append(':').append(item.getPlannedStartTime()).append(':').append(item.getPlannedEndTime())
                .append(':').append(item.getPlannedWorksiteCode()).append(':').append(item.getPlannedResourceSlotNo())
                .append(':').append(item.getPlannedDeviceCode()).append(':').append(item.getPlannedWorkerCode())
                .append(':').append(item.getPlannedToolingCodes()));
        facts.arrangements.forEach(item -> raw.append("|A:").append(item.getId()).append(':').append(item.getProduceTaskCode())
                .append(':').append(item.getStatus()).append(':').append(item.getStartTime()).append(':')
                .append(item.getFinishTime()).append(':').append(item.getCompleteNum()).append(':')
                .append(item.getPlannedStartTime()).append(':').append(item.getPlannedEndTime())
                .append(':').append(item.getPlannedWorksiteCode()).append(':').append(item.getPlannedResourceSlotNo())
                .append(':').append(item.getPlannedDeviceCode()).append(':').append(item.getPlannedWorkerCode())
                .append(':').append(item.getPlannedToolingCodes()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("MES执行状态指纹生成失败");
        }
    }

    private MesApsLocalScheduleDTO.ApplyReceipt receipt(MesApsScheduleSyncEntity entity) {
        return new MesApsLocalScheduleDTO.ApplyReceipt().setRequestId(entity.getRequestId()).setPlanNo(entity.getPlanNo())
                .setReplanCaseId(entity.getReplanCaseId()).setUpdatedOperationCount(entity.getUpdatedOperationCount())
                .setBeforeExecutionFingerprint(entity.getBeforeExecutionFingerprint())
                .setAfterExecutionFingerprint(entity.getAfterExecutionFingerprint()).setAppliedTime(entity.getAppliedTime());
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private void requireText(String value, String message) {
        require(StrUtil.isNotBlank(value), message);
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    private record Facts(String planNo, String factoryCode, List<MesTaskEntity> tasks,
                         List<MesProduceTaskEntity> produceTasks, List<MesTaskArrangeEntity> arrangements) {
    }
}
