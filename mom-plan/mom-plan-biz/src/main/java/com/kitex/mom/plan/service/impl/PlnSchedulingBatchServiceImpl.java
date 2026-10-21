package com.kitex.mom.plan.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.plan.dto.PlnSchedulingBatchConfigureDTO;
import com.kitex.mom.plan.dto.PlnSchedulingBatchLedgerDTO;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.PlnSchedulingBatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PlnSchedulingBatchServiceImpl implements PlnSchedulingBatchService {
    private final PlnSchedulingBatchMapper batchMapper;
    private final PlnSchedulingBatchAllocationMapper allocationMapper;
    private final PlnSchedulingBatchChangeMapper changeMapper;
    private final PlnSchedulingTaskMapper taskMapper;
    private final PlnSchedulingTaskDetailMapper detailMapper;
    private final PlnSchedulingOperationMapper operationMapper;
    private final PlnIssueMapper issueMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void initializeSingleBatches(Long taskId, List<PlnIssueEntity> issues) {
        require(taskId != null && issues != null && !issues.isEmpty(), "排产批次初始化缺少任务或计划");
        require(batchMapper.selectCount(new LambdaQueryWrapper<PlnSchedulingBatchEntity>()
                .eq(PlnSchedulingBatchEntity::getTaskId, taskId)) == 0, "排产任务已存在批次账，禁止覆盖");
        List<PlnSchedulingTaskDetailEntity> details = detailMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingTaskDetailEntity>().eq(PlnSchedulingTaskDetailEntity::getTaskId, taskId));
        Map<Long, PlnSchedulingTaskDetailEntity> detailByIssue = details.stream()
                .collect(Collectors.toMap(PlnSchedulingTaskDetailEntity::getIssueId, Function.identity()));
        int seq = 1;
        for (PlnIssueEntity issue : issues) {
            require(issue.getId() != null && positive(issue.getNum()), "排产计划ID或数量无效");
            PlnSchedulingTaskDetailEntity detail = detailByIssue.get(issue.getId());
            require(detail != null && same(issue.getNum(), detail.getNum()), "排产明细数量与计划不一致");
            List<PlnSchedulingOperationEntity> operations = operationMapper.selectList(
                    new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                            .eq(PlnSchedulingOperationEntity::getTaskId, taskId)
                            .eq(PlnSchedulingOperationEntity::getIssueId, issue.getId())
                            .orderByAsc(PlnSchedulingOperationEntity::getOperationSeq));
            require(!operations.isEmpty(), "计划[" + issue.getIssueNo() + "]没有工序排程，不能建立数量账");
            require(operations.stream().allMatch(item -> same(issue.getNum(), item.getPlannedQuantity())),
                    "计划[" + issue.getIssueNo() + "]工序数量与计划量不一致");

            String batchNo = "APSB-" + taskId + "-" + String.format("%04d", seq);
            PlnSchedulingBatchAllocationEntity allocation = new PlnSchedulingBatchAllocationEntity()
                    .setTaskId(taskId).setBatchNo(batchNo).setAllocationSeq(1).setIssueId(issue.getId())
                    .setIssueNo(issue.getIssueNo()).setIssueQuantitySnapshot(issue.getNum())
                    .setAllocatedQuantity(issue.getNum()).setProductCode(issue.getProductCode())
                    .setFactoryCode(issue.getFactoryCode());
            String allocationSnapshot = JSONUtil.toJsonStr(List.of(allocation));
            PlnSchedulingBatchEntity batch = new PlnSchedulingBatchEntity().setTaskId(taskId).setBatchNo(batchNo)
                    .setBatchType("SINGLE").setBatchSeq(seq).setPrimaryIssueId(issue.getId())
                    .setPrimaryIssueNo(issue.getIssueNo()).setProductCode(issue.getProductCode())
                    .setProductName(issue.getProductName()).setRouteCode(detail.getProcessRouteCode())
                    .setEngineeringReleaseId(detail.getEngineeringReleaseId())
                    .setEngineeringContentHash(detail.getEngineeringContentHash())
                    .setEngineeringVersionNo(detail.getEngineeringVersionNo())
                    .setEngineeringRouteId(detail.getEngineeringRouteId())
                    .setEngineeringBomId(detail.getEngineeringBomId())
                    .setEngineeringBomVersion(detail.getEngineeringBomVersion())
                    .setPlannedQuantity(issue.getNum()).setAllocationCount(1).setAllocationSnapshot(allocationSnapshot)
                    .setScheduledStartTime(operations.stream().map(PlnSchedulingOperationEntity::getScheduledStartTime)
                            .filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(null))
                    .setScheduledEndTime(operations.stream().map(PlnSchedulingOperationEntity::getScheduledEndTime)
                            .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null))
                    .setBatchStatus("PLANNED").setLockStatus(false).setFactoryCode(issue.getFactoryCode());
            batch.setSourceFingerprint(fingerprint(batchNo, issue.getId(), issue.getIssueNo(), issue.getProductCode(),
                    issue.getNum(), detail.getProcessRouteCode(), detail.getEngineeringReleaseId(),
                    detail.getEngineeringContentHash(), detail.getScheduledDate(), allocationSnapshot));
            require(batchMapper.insert(batch) == 1, "排产批次头保存失败");
            allocation.setBatchId(batch.getId());
            require(allocationMapper.insert(allocation) == 1, "排产批次来源分配保存失败");
            operationMapper.update(null, new LambdaUpdateWrapper<PlnSchedulingOperationEntity>()
                    .eq(PlnSchedulingOperationEntity::getTaskId, taskId)
                    .eq(PlnSchedulingOperationEntity::getIssueId, issue.getId())
                    .set(PlnSchedulingOperationEntity::getSchedulingBatchId, batch.getId())
                    .set(PlnSchedulingOperationEntity::getSchedulingBatchNo, batchNo)
                    .set(PlnSchedulingOperationEntity::getSchedulingBatchType, "SINGLE")
                    .set(PlnSchedulingOperationEntity::getBatchAllocationSnapshot, allocationSnapshot));
            seq++;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean configure(PlnSchedulingBatchConfigureDTO dto) {
        require(dto != null && dto.getTaskId() != null, "排产批次调整缺少任务ID");
        require(dto.getRequestId() != null && !dto.getRequestId().isBlank()
                && dto.getRequestId().length() <= 64, "请求号不能为空且不能超过64位");
        require(dto.getExpectedQuantityFingerprint() != null
                && !dto.getExpectedQuantityFingerprint().isBlank(), "缺少当前批次数量指纹");
        require(dto.getReason() != null && !dto.getReason().isBlank()
                && dto.getReason().length() <= 500, "调整原因不能为空且不能超过500字");
        require(dto.getBatches() != null && !dto.getBatches().isEmpty()
                && dto.getBatches().size() <= 200, "批次不能为空且单次最多调整200个批次");

        Long taskId = dto.getTaskId();
        require(taskMapper.acquireTransactionLock(taskId) != null, "排产任务不存在");
        PlnSchedulingTaskEntity task = taskMapper.selectById(taskId);
        require(task == null || !Boolean.TRUE.equals(task.getResultArchived()), "已归档结果不能拆合批");
        require(task != null && "official".equalsIgnoreCase(task.getMode())
                        && "WAIT_CONFIRM".equalsIgnoreCase(task.getTaskStatus()),
                "仅待确认的正式排产任务允许拆批或合批");

        String commandHash = fingerprint(taskId, dto.getRequestId(), dto.getReason(),
                JSONUtil.toJsonStr(dto.getBatches()));
        PlnSchedulingBatchChangeEntity existing = changeMapper.selectOne(
                new LambdaQueryWrapper<PlnSchedulingBatchChangeEntity>()
                        .eq(PlnSchedulingBatchChangeEntity::getTaskId, taskId)
                        .eq(PlnSchedulingBatchChangeEntity::getRequestId, dto.getRequestId())
                        .last("LIMIT 1"));
        if (existing != null) {
            require(Objects.equals(existing.getCommandHash(), commandHash),
                    "同一请求号已用于不同的批次调整命令");
            require("SUCCESS".equals(existing.getChangeStatus()), "此前批次调整未成功完成");
            return false;
        }

        PlnSchedulingBatchLedgerDTO before = ledger(taskId);
        require(Objects.equals(before.getQuantityFingerprint(), dto.getExpectedQuantityFingerprint()),
                "批次数量账已变化，请刷新后重新提交");
        require(before.getBatches().stream().noneMatch(item -> Boolean.TRUE.equals(item.getHeader().getLockStatus())),
                "任务存在已锁定批次，禁止重新拆合批");

        List<PlnSchedulingTaskDetailEntity> details = detailMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingTaskDetailEntity>()
                        .eq(PlnSchedulingTaskDetailEntity::getTaskId, taskId));
        require(!details.isEmpty(), "排产任务缺少计划明细");
        Map<Long, PlnSchedulingTaskDetailEntity> detailByIssue = details.stream()
                .collect(Collectors.toMap(PlnSchedulingTaskDetailEntity::getIssueId, Function.identity()));
        Map<Long, PlnIssueEntity> currentIssues = issueMapper.selectBatchIds(detailByIssue.keySet()).stream()
                .collect(Collectors.toMap(PlnIssueEntity::getId, Function.identity()));
        require(currentIssues.size() == details.size(), "排产任务中的计划已删除，禁止调整批次");
        for (PlnSchedulingTaskDetailEntity detail : details) {
            PlnIssueEntity current = currentIssues.get(detail.getIssueId());
            require(current != null && "0".equals(current.getStatus())
                            && !Boolean.TRUE.equals(current.getLockStatus()),
                    "计划[" + detail.getIssueNo() + "]已下达或已被锁定");
            require(same(detail.getNum(), current.getNum()),
                    "计划[" + detail.getIssueNo() + "]数量已变化");
            require(sameEngineeringBinding(detail, current),
                    "计划[" + detail.getIssueNo() + "]工程版本已变化，必须重新排产");
        }

        int allocationCount = dto.getBatches().stream()
                .map(PlnSchedulingBatchConfigureDTO.Batch::getAllocations)
                .filter(Objects::nonNull).mapToInt(List::size).sum();
        require(allocationCount <= 1000, "单次批次调整的来源分配不能超过1000行");
        Map<Long, BigDecimal> allocatedByIssue = new LinkedHashMap<>();
        Map<Long, Integer> batchOccurrencesByIssue = new HashMap<>();
        for (int batchIndex = 0; batchIndex < dto.getBatches().size(); batchIndex++) {
            PlnSchedulingBatchConfigureDTO.Batch requestedBatch = dto.getBatches().get(batchIndex);
            require(requestedBatch != null && requestedBatch.getAllocations() != null
                    && !requestedBatch.getAllocations().isEmpty(), "第" + (batchIndex + 1) + "个批次没有来源计划");
            Set<Long> issueIds = new LinkedHashSet<>();
            for (PlnSchedulingBatchConfigureDTO.Allocation allocation : requestedBatch.getAllocations()) {
                require(allocation != null && allocation.getIssueId() != null
                                && detailByIssue.containsKey(allocation.getIssueId()),
                        "第" + (batchIndex + 1) + "个批次包含非本任务计划");
                require(positive(allocation.getQuantity()), "批次来源分配数量必须大于0");
                require(issueIds.add(allocation.getIssueId()), "同一批次内不能重复引用同一计划");
                allocatedByIssue.merge(allocation.getIssueId(), allocation.getQuantity(), BigDecimal::add);
                batchOccurrencesByIssue.merge(allocation.getIssueId(), 1, Integer::sum);
            }
            validateMergeCompatibility(issueIds.stream().map(detailByIssue::get).toList(), batchIndex + 1);
        }
        for (PlnSchedulingTaskDetailEntity detail : details) {
            require(same(detail.getNum(), allocatedByIssue.get(detail.getIssueId())),
                    "计划[" + detail.getIssueNo() + "]分配量必须与计划量完全一致");
        }
        require(allocatedByIssue.size() == details.size(), "每张计划都必须完整分配到批次");

        operationMapper.delete(new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                .eq(PlnSchedulingOperationEntity::getTaskId, taskId));
        allocationMapper.delete(new LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity>()
                .eq(PlnSchedulingBatchAllocationEntity::getTaskId, taskId));
        batchMapper.delete(new LambdaQueryWrapper<PlnSchedulingBatchEntity>()
                .eq(PlnSchedulingBatchEntity::getTaskId, taskId));

        for (int batchIndex = 0; batchIndex < dto.getBatches().size(); batchIndex++) {
            int batchSeq = batchIndex + 1;
            PlnSchedulingBatchConfigureDTO.Batch requestedBatch = dto.getBatches().get(batchIndex);
            List<PlnSchedulingBatchAllocationEntity> allocations = new ArrayList<>();
            int allocationSeq = 1;
            String batchNo = "APSB-" + taskId + "-" + String.format("%04d", batchSeq);
            for (PlnSchedulingBatchConfigureDTO.Allocation requestedAllocation : requestedBatch.getAllocations()) {
                PlnSchedulingTaskDetailEntity detail = detailByIssue.get(requestedAllocation.getIssueId());
                allocations.add(new PlnSchedulingBatchAllocationEntity().setTaskId(taskId).setBatchNo(batchNo)
                        .setAllocationSeq(allocationSeq++).setIssueId(detail.getIssueId())
                        .setIssueNo(detail.getIssueNo()).setIssueQuantitySnapshot(detail.getNum())
                        .setAllocatedQuantity(requestedAllocation.getQuantity()).setProductCode(detail.getProductCode())
                        .setFactoryCode(detail.getFactoryCode()));
            }
            PlnSchedulingTaskDetailEntity primary = detailByIssue.get(allocations.get(0).getIssueId());
            String batchType = allocations.size() > 1 ? "MERGED"
                    : batchOccurrencesByIssue.getOrDefault(primary.getIssueId(), 0) > 1 ? "SPLIT" : "SINGLE";
            BigDecimal batchQuantity = sum(allocations.stream()
                    .map(PlnSchedulingBatchAllocationEntity::getAllocatedQuantity).toList());
            String allocationSnapshot = JSONUtil.toJsonStr(allocations);
            PlnSchedulingBatchEntity batch = new PlnSchedulingBatchEntity().setTaskId(taskId).setBatchNo(batchNo)
                    .setBatchType(batchType).setBatchSeq(batchSeq).setPrimaryIssueId(primary.getIssueId())
                    .setPrimaryIssueNo(primary.getIssueNo()).setProductCode(primary.getProductCode())
                    .setProductName(primary.getProductName()).setRouteCode(primary.getProcessRouteCode())
                    .setEngineeringReleaseId(primary.getEngineeringReleaseId())
                    .setEngineeringContentHash(primary.getEngineeringContentHash())
                    .setEngineeringVersionNo(primary.getEngineeringVersionNo())
                    .setEngineeringRouteId(primary.getEngineeringRouteId())
                    .setEngineeringBomId(primary.getEngineeringBomId())
                    .setEngineeringBomVersion(primary.getEngineeringBomVersion())
                    .setPlannedQuantity(batchQuantity).setAllocationCount(allocations.size())
                    .setAllocationSnapshot(allocationSnapshot).setRequestedStartDate(requestedBatch.getRequestedStartDate())
                    .setBatchStatus("PENDING_SCHEDULE").setLockStatus(false).setFactoryCode(primary.getFactoryCode());
            batch.setSourceFingerprint(fingerprint(batchNo, batchType, batchQuantity,
                    primary.getEngineeringReleaseId(), primary.getEngineeringContentHash(),
                    requestedBatch.getRequestedStartDate(), allocationSnapshot));
            require(batchMapper.insert(batch) == 1, "批次[" + batchNo + "]保存失败");
            for (PlnSchedulingBatchAllocationEntity allocation : allocations) {
                allocation.setBatchId(batch.getId());
                require(allocationMapper.insert(allocation) == 1, "批次[" + batchNo + "]来源分配保存失败");
            }
        }

        PlnSchedulingBatchLedgerDTO after = ledger(taskId);
        require(Boolean.TRUE.equals(after.getQuantityBalanced()), "拆合批后数量不守恒");
        require(!Objects.equals(before.getQuantityFingerprint(), after.getQuantityFingerprint()),
                "批次、分配数量和期望开工日均未发生变化，无需保存");
        PlnSchedulingBatchChangeEntity change = new PlnSchedulingBatchChangeEntity().setTaskId(taskId)
                .setRequestId(dto.getRequestId()).setCommandHash(commandHash)
                .setBeforeQuantityFingerprint(before.getQuantityFingerprint())
                .setAfterQuantityFingerprint(after.getQuantityFingerprint())
                .setBeforeSnapshot(JSONUtil.toJsonStr(before)).setAfterSnapshot(JSONUtil.toJsonStr(after))
                .setReason(dto.getReason().trim()).setChangeStatus("SUCCESS")
                .setChangedTime(LocalDateTime.now()).setFactoryCode(task.getFactoryCode());
        require(changeMapper.insert(change) == 1, "批次调整操作账保存失败");
        return true;
    }

    @Override
    public PlnSchedulingBatchLedgerDTO ledger(Long taskId) {
        require(taskId != null, "排产任务ID不能为空");
        List<PlnSchedulingBatchEntity> batches = batchMapper.selectList(new LambdaQueryWrapper<PlnSchedulingBatchEntity>()
                .eq(PlnSchedulingBatchEntity::getTaskId, taskId).orderByAsc(PlnSchedulingBatchEntity::getBatchSeq));
        List<PlnSchedulingBatchAllocationEntity> allocations = allocationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity>().eq(PlnSchedulingBatchAllocationEntity::getTaskId, taskId)
                        .orderByAsc(PlnSchedulingBatchAllocationEntity::getBatchNo)
                        .orderByAsc(PlnSchedulingBatchAllocationEntity::getAllocationSeq));
        List<PlnSchedulingTaskDetailEntity> details = detailMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingTaskDetailEntity>().eq(PlnSchedulingTaskDetailEntity::getTaskId, taskId));
        BigDecimal source = sum(details.stream().map(PlnSchedulingTaskDetailEntity::getNum).toList());
        BigDecimal allocated = sum(allocations.stream().map(PlnSchedulingBatchAllocationEntity::getAllocatedQuantity).toList());
        BigDecimal batched = sum(batches.stream().map(PlnSchedulingBatchEntity::getPlannedQuantity).toList());
        Map<Long, List<PlnSchedulingBatchAllocationEntity>> byBatch = allocations.stream()
                .collect(Collectors.groupingBy(PlnSchedulingBatchAllocationEntity::getBatchId));
        String fp = fingerprint(taskId, source, allocated, batched, batches.stream().map(PlnSchedulingBatchEntity::getSourceFingerprint).toList());
        return new PlnSchedulingBatchLedgerDTO().setTaskId(taskId).setSourceQuantity(source)
                .setAllocatedQuantity(allocated).setBatchQuantity(batched)
                .setQuantityBalanced(same(source, allocated) && same(allocated, batched)).setQuantityFingerprint(fp)
                .setBatches(batches.stream().map(batch -> new PlnSchedulingBatchLedgerDTO.Batch().setHeader(batch)
                        .setAllocations(byBatch.getOrDefault(batch.getId(), List.of()))).toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void validateBeforeLock(Long taskId) {
        if (batchMapper.selectCount(new LambdaQueryWrapper<PlnSchedulingBatchEntity>()
                .eq(PlnSchedulingBatchEntity::getTaskId, taskId)) == 0) {
            List<PlnSchedulingTaskDetailEntity> legacyDetails = detailMapper.selectList(
                    new LambdaQueryWrapper<PlnSchedulingTaskDetailEntity>()
                            .eq(PlnSchedulingTaskDetailEntity::getTaskId, taskId));
            List<PlnIssueEntity> legacyIssues = legacyDetails.isEmpty() ? List.of()
                    : issueMapper.selectBatchIds(legacyDetails.stream()
                    .map(PlnSchedulingTaskDetailEntity::getIssueId).filter(Objects::nonNull).toList());
            require(legacyIssues.size() == legacyDetails.size(), "历史待锁定任务无法完整回建批次数量账");
            initializeSingleBatches(taskId, legacyIssues);
        }
        PlnSchedulingBatchLedgerDTO ledger = ledger(taskId);
        require(!ledger.getBatches().isEmpty(), "排产任务缺少批次数量账");
        require(Boolean.TRUE.equals(ledger.getQuantityBalanced()), "排产任务来源量、分配量与批次量不守恒");
        List<PlnSchedulingTaskDetailEntity> details = detailMapper.selectList(new LambdaQueryWrapper<PlnSchedulingTaskDetailEntity>()
                .eq(PlnSchedulingTaskDetailEntity::getTaskId, taskId));
        Map<Long, PlnIssueEntity> current = issueMapper.selectBatchIds(details.stream()
                        .map(PlnSchedulingTaskDetailEntity::getIssueId).toList()).stream()
                .collect(Collectors.toMap(PlnIssueEntity::getId, Function.identity()));
        Map<Long, BigDecimal> allocatedByIssue = ledger.getBatches().stream().flatMap(item -> item.getAllocations().stream())
                .collect(Collectors.groupingBy(PlnSchedulingBatchAllocationEntity::getIssueId,
                        Collectors.reducing(BigDecimal.ZERO, PlnSchedulingBatchAllocationEntity::getAllocatedQuantity, BigDecimal::add)));
        for (PlnSchedulingTaskDetailEntity detail : details) {
            PlnIssueEntity issue = current.get(detail.getIssueId());
            require(issue != null && "0".equals(issue.getStatus()) && !Boolean.TRUE.equals(issue.getLockStatus()),
                    "计划[" + detail.getIssueNo() + "]已删除、已下达或已被其他任务锁定");
            require(same(detail.getNum(), issue.getNum()), "计划[" + detail.getIssueNo() + "]数量已变化");
            require(sameEngineeringBinding(detail, issue),
                    "计划[" + detail.getIssueNo() + "]工程版本已变化，必须重新排产");
            require(same(issue.getNum(), allocatedByIssue.get(detail.getIssueId())),
                    "计划[" + detail.getIssueNo() + "]批次分配数量不守恒");
        }
        for (PlnSchedulingBatchLedgerDTO.Batch item : ledger.getBatches()) {
            PlnSchedulingBatchEntity batch = item.getHeader();
            BigDecimal allocationTotal = sum(item.getAllocations().stream()
                    .map(PlnSchedulingBatchAllocationEntity::getAllocatedQuantity).toList());
            require(positive(batch.getPlannedQuantity()) && same(batch.getPlannedQuantity(), allocationTotal),
                    "批次[" + batch.getBatchNo() + "]头行数量不守恒");
            require(completeEngineeringBinding(batch), "批次[" + batch.getBatchNo() + "]缺少精确工程版本快照");
            List<PlnSchedulingOperationEntity> operations = operationMapper.selectList(
                    new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                            .eq(PlnSchedulingOperationEntity::getTaskId, taskId)
                            .eq(PlnSchedulingOperationEntity::getSchedulingBatchId, batch.getId()));
            require(!operations.isEmpty(), "批次[" + batch.getBatchNo() + "]缺少工序排程");
            require(operations.stream().allMatch(op -> same(batch.getPlannedQuantity(), op.getPlannedQuantity())
                            && Objects.equals(batch.getAllocationSnapshot(), op.getBatchAllocationSnapshot())),
                    "批次[" + batch.getBatchNo() + "]工序数量或来源快照不一致");
        }
    }

    @Override
    public void lockIssueBatches(Long taskId, Long issueId, LocalDateTime lockTime) {
        require(taskId != null && issueId != null && lockTime != null, "批次锁定参数不完整");
        List<Long> batchIds = allocationMapper.selectList(new LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity>()
                        .eq(PlnSchedulingBatchAllocationEntity::getTaskId, taskId)
                        .eq(PlnSchedulingBatchAllocationEntity::getIssueId, issueId))
                .stream().map(PlnSchedulingBatchAllocationEntity::getBatchId).distinct().toList();
        require(!batchIds.isEmpty(), "计划缺少可锁定排产批次");
        batchMapper.update(null, new LambdaUpdateWrapper<PlnSchedulingBatchEntity>()
                .eq(PlnSchedulingBatchEntity::getTaskId, taskId).in(PlnSchedulingBatchEntity::getId, batchIds)
                .set(PlnSchedulingBatchEntity::getBatchStatus, "LOCKED")
                .set(PlnSchedulingBatchEntity::getLockStatus, true)
                .set(PlnSchedulingBatchEntity::getLockTime, lockTime));
    }

    private BigDecimal sum(List<BigDecimal> values) {
        return values.stream().filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private boolean same(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private void validateMergeCompatibility(List<PlnSchedulingTaskDetailEntity> details, int batchSeq) {
        if (details.size() <= 1) return;
        PlnSchedulingTaskDetailEntity first = details.get(0);
        for (int index = 1; index < details.size(); index++) {
            PlnSchedulingTaskDetailEntity other = details.get(index);
            require(Objects.equals(first.getFactoryCode(), other.getFactoryCode())
                            && Objects.equals(first.getProductCode(), other.getProductCode())
                            && Objects.equals(first.getCustomerCode(), other.getCustomerCode())
                            && Objects.equals(first.getProcessRouteCode(), other.getProcessRouteCode())
                            && Objects.equals(first.getEngineeringReleaseId(), other.getEngineeringReleaseId())
                            && Objects.equals(first.getEngineeringContentHash(), other.getEngineeringContentHash())
                            && Objects.equals(first.getDemandTime(), other.getDemandTime())
                            && Objects.equals(first.getMaterialAvailableDate(), other.getMaterialAvailableDate())
                            && Objects.equals(first.getMaterialAvailableTime(), other.getMaterialAvailableTime())
                            && Objects.equals(first.getMaterialConstraintSnapshot(), other.getMaterialConstraintSnapshot()),
                    "第" + batchSeq + "个合批不满足同工厂、同产品、同客户、同工程版本、同需求日和同齐套证据要求");
        }
    }

    /** 计划在排程后不得换绑工程版本，否则原工序、工装与工时结果全部失效。 */
    private boolean sameEngineeringBinding(PlnSchedulingTaskDetailEntity detail, PlnIssueEntity issue) {
        return detail.getEngineeringReleaseId() != null
                && detail.getEngineeringContentHash() != null
                && Objects.equals(detail.getEngineeringReleaseId(), issue.getEngineeringReleaseId())
                && Objects.equals(detail.getEngineeringContentHash(), issue.getEngineeringContentHash())
                && Objects.equals(detail.getEngineeringRouteId(), issue.getEngineeringRouteId())
                && Objects.equals(detail.getEngineeringBomId(), issue.getEngineeringBomId());
    }

    private boolean completeEngineeringBinding(PlnSchedulingBatchEntity batch) {
        return batch.getEngineeringReleaseId() != null
                && batch.getEngineeringContentHash() != null
                && batch.getEngineeringContentHash().matches("[0-9a-f]{64}")
                && batch.getEngineeringRouteId() != null
                && batch.getEngineeringBomId() != null;
    }

    private String fingerprint(Object... values) {
        try {
            String raw = Arrays.stream(values).map(String::valueOf).collect(Collectors.joining("|"));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("排产批次数量指纹生成失败");
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
