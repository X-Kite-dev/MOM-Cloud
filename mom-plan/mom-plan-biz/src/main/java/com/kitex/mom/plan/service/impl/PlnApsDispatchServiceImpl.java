package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyDTO;
import com.kitex.mom.plan.dto.PlnApsCaseDTO;
import com.kitex.mom.plan.dto.PlnApsMesDispatchDTO;
import com.kitex.mom.plan.dto.PlnFormalPlanContractDTO;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.PlnApsCaseService;
import com.kitex.mom.plan.service.PlnApsDispatchService;
import com.kitex.mom.plan.service.PlnFactoryPlanningAuthorityService;
import com.kitex.mom.plan.service.PlnFormalPlanContractService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * PLAN 到 MES 的事务 Outbox 实现。
 */
@Service
@RequiredArgsConstructor
public class PlnApsDispatchServiceImpl
        extends ServiceImpl<PlnApsMesDispatchOutboxMapper, PlnApsMesDispatchOutboxEntity>
        implements PlnApsDispatchService {

    private final PlnApsCaseService caseService;
    private final PlnSchedulingTaskDetailMapper taskDetailMapper;
    private final PlnSchedulingOperationMapper operationMapper;
    private final PlnSchedulingBatchAllocationMapper batchAllocationMapper;
    private final PlnIssueMapper issueMapper;
    private final PlnFactoryPlanningAuthorityService authorityService;
    private final PlnFormalPlanContractService formalPlanContractService;
    private final ObjectMapper objectMapper;

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Long parseId(String value, String label) {
        try {
            Long id = Long.valueOf(value);
            require(id > 0, label + "必须为正整数");
            return id;
        } catch (RuntimeException ex) {
            if (ex instanceof CheckedException checked) throw checked;
            throw new CheckedException(label + "格式不正确");
        }
    }

    private static boolean isFingerprint(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String text(Long value) {
        return value == null ? null : value.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnApsMesDispatchDTO.DispatchView enqueue(Long caseId, Long selectedTaskId,
                                                     String publicationFingerprint) {
        require(caseId != null && selectedTaskId != null && isFingerprint(publicationFingerprint),
                "MES下达入账缺少案件、候选或正式发布指纹");
        PlnApsCaseDTO apsCase = caseService.detail(caseId);
        require("PUBLISHED".equals(apsCase.getCaseStatus())
                        && Objects.equals(apsCase.getSelectedTaskId(), selectedTaskId.toString())
                        && parseId(apsCase.getScenarioId(), "情景主键") > 0,
                "只有完成A3发布且候选一致的APS案件可以下达MES");
        MdmFactoryPlanningPolicyDTO policy = authorityService.requireCurrentPolicy(apsCase.getFactoryCode());
        require(Objects.equals(apsCase.getPlanningPolicyId(), parseId(policy.getId(), "计划权威策略主键"))
                        && Objects.equals(apsCase.getPlanningPolicyVersion(), policy.getVersionNo())
                        && Objects.equals(apsCase.getPlanningPolicyFingerprint(), policy.getContentFingerprint())
                        && Objects.equals(apsCase.getMrpAuthority(), policy.getMrpAuthority()),
                "A3发布后工厂计划权威已变化，禁止按旧策略下达MES");
        PlnFormalPlanContractDTO formalContract = formalPlanContractService.getVerifiedContract(
                apsCase.getFormalPlanContractNo(), apsCase.getFormalPlanContractVersion());
        require(Objects.equals(formalContract.getSourceContentFingerprint(),
                        apsCase.getFormalPlanContractFingerprint())
                        && Objects.equals(formalContract.getPlanningPolicyId(), policy.getId()),
                "A3发布冻结的正式计划合同已失效或与当前策略不一致");
        authorityService.revalidate(policy);
        Long tenantId = TenantContextHolder.getTenantId();
        require(tenantId != null && tenantId > 0, "MES下达缺少租户上下文");
        baseMapper.lock("APS_MES_OUTBOX:" + tenantId + ':' + apsCase.getFactoryCode() + ':'
                + caseId + ':' + publicationFingerprint);
        PlnApsMesDispatchOutboxEntity existing = baseMapper.selectOne(
                new LambdaQueryWrapper<PlnApsMesDispatchOutboxEntity>()
                        .eq(PlnApsMesDispatchOutboxEntity::getTenantId, tenantId)
                        .eq(PlnApsMesDispatchOutboxEntity::getFactoryCode, apsCase.getFactoryCode())
                        .eq(PlnApsMesDispatchOutboxEntity::getCaseId, caseId)
                        .eq(PlnApsMesDispatchOutboxEntity::getPublicationFingerprint, publicationFingerprint)
                        .last("LIMIT 1"));
        if (existing != null) {
            caseService.recordDispatchStatus(caseId, caseDispatchStatus(existing.getStatus()),
                    "APS-MES-RECOVER:" + existing.getId() + ':' + existing.getStatus(),
                    "复用既有MES下达Outbox并同步案件汇总状态：" + existing.getStatus());
            return toView(existing);
        }

        List<PlnSchedulingTaskDetailEntity> details = taskDetailMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingTaskDetailEntity>()
                        .eq(PlnSchedulingTaskDetailEntity::getTaskId, selectedTaskId)
                        .eq(PlnSchedulingTaskDetailEntity::getFactoryCode, apsCase.getFactoryCode())
                        .orderByAsc(PlnSchedulingTaskDetailEntity::getIssueId));
        require(!details.isEmpty(), "已发布APS候选没有可下达的生产计划");
        List<PlnSchedulingOperationEntity> operations = operationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                        .eq(PlnSchedulingOperationEntity::getTaskId, selectedTaskId)
                        .eq(PlnSchedulingOperationEntity::getFactoryCode, apsCase.getFactoryCode())
                        .orderByAsc(PlnSchedulingOperationEntity::getIssueId)
                        .orderByAsc(PlnSchedulingOperationEntity::getOperationSeq)
                        .orderByAsc(PlnSchedulingOperationEntity::getId));
        List<PlnSchedulingBatchAllocationEntity> allocations = batchAllocationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity>()
                        .eq(PlnSchedulingBatchAllocationEntity::getTaskId, selectedTaskId)
                        .eq(PlnSchedulingBatchAllocationEntity::getFactoryCode, apsCase.getFactoryCode())
                        .orderByAsc(PlnSchedulingBatchAllocationEntity::getBatchId)
                        .orderByAsc(PlnSchedulingBatchAllocationEntity::getAllocationSeq));
        List<PlnIssueEntity> sourceIssues = issueMapper.selectBatchIds(
                details.stream().map(PlnSchedulingTaskDetailEntity::getIssueId).distinct().toList());
        require(sourceIssues.size() == details.stream().map(PlnSchedulingTaskDetailEntity::getIssueId).distinct().count(),
                "已发布APS候选的来源生产计划不完整");
        List<List<Long>> groups = dispatchGroups(details, allocations);
        List<PlnApsMesDispatchDTO.PlanItem> items = new ArrayList<>();
        for (List<Long> issueIds : groups) {
            List<PlnSchedulingTaskDetailEntity> groupDetails = details.stream()
                    .filter(detail -> issueIds.contains(detail.getIssueId())).toList();
            List<PlnSchedulingOperationEntity> issueOperations = operations.stream()
                    .filter(row -> issueIds.contains(row.getIssueId())).toList();
            List<PlnIssueEntity> groupIssues = sourceIssues.stream()
                    .filter(row -> issueIds.contains(row.getId())).sorted(java.util.Comparator.comparing(PlnIssueEntity::getId))
                    .toList();
            List<PlnSchedulingBatchAllocationEntity> groupAllocations = allocations.stream()
                    .filter(row -> issueIds.contains(row.getIssueId())).toList();
            require(groupDetails.size() == issueIds.size() && groupDetails.stream().allMatch(detail -> Boolean.TRUE.equals(detail.getLockStatus()))
                            && groupIssues.size() == issueIds.size() && !issueOperations.isEmpty()
                            && issueOperations.stream().allMatch(row -> Boolean.TRUE.equals(row.getLockStatus())
                            && !Boolean.TRUE.equals(row.getHardBlocked())),
                    "下达单元中的计划工序未完整锁定或存在硬阻断，禁止下达MES");
            List<PlnSchedulingOperationEntity> workshops = issueOperations.stream()
                    .filter(row -> "WORKSHOP".equals(row.getResourceType()) && StrUtil.isNotBlank(row.getResourceCode()))
                    .toList();
            String workshopCode = workshops.stream().map(PlnSchedulingOperationEntity::getResourceCode)
                    .distinct().count() == 1 ? workshops.get(0).getResourceCode() : null;
            String workshopName = workshopCode == null ? null : workshops.stream()
                    .filter(row -> Objects.equals(workshopCode, row.getResourceCode()))
                    .map(PlnSchedulingOperationEntity::getResourceName).filter(StrUtil::isNotBlank)
                    .findFirst().orElse(null);
            PlnSchedulingTaskDetailEntity primary = groupDetails.get(0);
            List<String> issueIdTexts = issueIds.stream().map(String::valueOf).toList();
            List<String> issueNos = groupDetails.stream().map(PlnSchedulingTaskDetailEntity::getIssueNo).toList();
            items.add(new PlnApsMesDispatchDTO.PlanItem().setDispatchKey(String.join(",", issueIdTexts))
                    .setIssueId(text(primary.getIssueId())).setIssueNo(primary.getIssueNo())
                    .setIssueIds(issueIdTexts).setIssueNos(issueNos).setProductCode(primary.getProductCode())
                    .setProductName(primary.getProductName()).setWorkshopCode(workshopCode)
                    .setWorkshopName(workshopName).setOperationCount(issueOperations.size())
                    .setDispatchEvidenceFingerprint(dispatchEvidence(
                            groupIssues, groupDetails, issueOperations, groupAllocations))
                    .setOperationEvidenceFingerprint(sha256(json(issueOperations))));
        }
        String requestId = "APS-MES-" + caseId + '-' + publicationFingerprint.substring(0, 16);
        PlnApsMesDispatchDTO.Envelope envelope = new PlnApsMesDispatchDTO.Envelope()
                .setContractVersion("PLAN_APS_MES_DISPATCH_V1").setRequestId(requestId)
                .setCaseId(caseId.toString()).setScenarioId(apsCase.getScenarioId())
                .setSelectedTaskId(selectedTaskId.toString()).setPublicationFingerprint(publicationFingerprint)
                .setFactoryCode(apsCase.getFactoryCode()).setMrpAuthority(apsCase.getMrpAuthority())
                .setPlanningPolicyId(policy.getId()).setPlanningPolicyVersion(policy.getVersionNo())
                .setPlanningPolicyFingerprint(policy.getContentFingerprint())
                .setProductionOrderSyncMode(policy.getProductionOrderSyncMode())
                .setFormalPlanContractNo(apsCase.getFormalPlanContractNo())
                .setFormalPlanContractVersion(apsCase.getFormalPlanContractVersion())
                .setFormalPlanContractFingerprint(apsCase.getFormalPlanContractFingerprint())
                .setItems(List.copyOf(items));
        String payload = json(envelope);
        String requestFingerprint = sha256(payload);
        String operator = SecurityUtils.getUser().getUsername();
        PlnApsMesDispatchOutboxEntity row = new PlnApsMesDispatchOutboxEntity()
                .setCaseId(caseId).setScenarioId(parseId(apsCase.getScenarioId(), "情景主键"))
                .setSelectedTaskId(selectedTaskId).setPublicationFingerprint(publicationFingerprint)
                .setRequestId(requestId).setRequestFingerprint(requestFingerprint).setPayloadJson(payload)
                .setStatus("PENDING").setAttemptCount(0).setDispatchedCount(0).setFailedCount(items.size())
                .setQueuedTime(LocalDateTime.now()).setFactoryCode(apsCase.getFactoryCode());
        row.setId(IdUtil.getSnowflakeNextId());
        row.setTenantId(tenantId);
        row.setCreateBy(operator);
        require(baseMapper.insert(row) == 1, "MES下达Outbox入账失败");
        caseService.recordDispatchStatus(caseId, "PENDING", "APS-MES-QUEUE:" + row.getId(),
                "A3正式发布结果已写入MES事务Outbox，等待异步下达");
        return toView(row);
    }

    @Override
    public PlnApsMesDispatchDTO.DispatchView detail(Long caseId) {
        PlnApsCaseDTO apsCase = caseService.detail(caseId);
        return toView(currentDispatch(apsCase, false, false));
    }

    /** 原接口按案件办理，仅在真实下达记录唯一时兼容旧客户端。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnApsMesDispatchDTO.DispatchView retry(Long caseId, String reason) {
        PlnApsCaseDTO apsCase = caseService.detail(caseId);
        return requeue(currentDispatch(apsCase, true, true), reason);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnApsMesDispatchDTO.DispatchView retryConfirmed(Long caseId, Long outboxId,
            PlnApsMesDispatchDTO.ConfirmedRetryCommand command) {
        PlnApsCaseDTO apsCase = caseService.detail(caseId);
        PlnApsMesDispatchOutboxEntity row = currentDispatch(apsCase, true, false);
        require(command != null && Objects.equals(row.getId(), outboxId)
                        && StrUtil.isNotBlank(command.getRequestId())
                        && Objects.equals(row.getRequestId(), command.getRequestId()),
                "当前下达单与已确认的原单不一致，请刷新后重新核对");
        require(command.getAttemptCount() != null
                        && Objects.equals(row.getAttemptCount(), command.getAttemptCount())
                        && Objects.equals(row.getStatus(), command.getStatus()),
                "MES下达状态或发送轮次已变化，请核对最新回执");
        return requeue(row, command.getReason());
    }

    /** 读与写核对同一正式候选。写入时持行锁，旧客户端未提供单号时检查歧义。 */
    private PlnApsMesDispatchOutboxEntity currentDispatch(PlnApsCaseDTO apsCase, boolean lock, boolean unique) {
        Long tenantId = TenantContextHolder.getTenantId();
        require(tenantId != null && tenantId > 0, "读取MES下达账缺少租户上下文");
        var query = new LambdaQueryWrapper<PlnApsMesDispatchOutboxEntity>()
                .eq(PlnApsMesDispatchOutboxEntity::getTenantId, tenantId)
                .eq(PlnApsMesDispatchOutboxEntity::getFactoryCode, apsCase.getFactoryCode())
                .eq(PlnApsMesDispatchOutboxEntity::getCaseId, parseId(apsCase.getId(), "案件主键"))
                .orderByDesc(PlnApsMesDispatchOutboxEntity::getId);
        String limit = unique ? "LIMIT 2" : "LIMIT 1";
        var rows = baseMapper.selectList(query.last(lock ? limit + " FOR UPDATE" : limit));
        require(!rows.isEmpty(), "APS案件尚未生成MES下达队列");
        require(rows.size() == 1, "该案件存在多次下达，请在执行跟踪中确认具体下达单");
        var row = rows.get(0);
        require(row.getScenarioId() != null && row.getSelectedTaskId() != null
                        && Objects.equals(text(row.getScenarioId()), apsCase.getScenarioId())
                        && Objects.equals(text(row.getSelectedTaskId()), apsCase.getSelectedTaskId()),
                "下达单与案件的正式情景或候选不一致，请核对发布记录");
        return row;
    }

    /** 原单已锁定，重新入队保留所有逐项回执，工作器只续办未成功的单元。 */
    private PlnApsMesDispatchDTO.DispatchView requeue(PlnApsMesDispatchOutboxEntity row, String reason) {
        require(StrUtil.isNotBlank(reason) && reason.trim().length() <= 500,
                "MES下达重试原因不能为空且不能超过500个字符");
        require(List.of("PARTIAL", "RETRY_WAIT", "MANUAL_REVIEW", "DEAD_LETTER").contains(row.getStatus()),
                "当前MES下达状态不允许人工重试");
        row.setStatus("PENDING").setNextRetryTime(LocalDateTime.now()).setLeaseToken(null).setLeaseUntil(null)
                .setLastError("人工重试：" + reason.trim()).setUpdateBy(SecurityUtils.getUser().getUsername());
        require(baseMapper.updateById(row) == 1, "MES下达重试入队失败");
        caseService.recordDispatchStatus(row.getCaseId(), "DISPATCHING",
                "APS-MES-REQUEUE:" + row.getId() + ':' + (Objects.requireNonNullElse(row.getAttemptCount(), 0) + 1),
                "有权限人员确认后重新排队MES下达：" + reason.trim());
        return toView(row);
    }

    @Override
    public void verifyFrozenItem(Long selectedTaskId, PlnApsMesDispatchDTO.PlanItem item) {
        require(selectedTaskId != null && selectedTaskId > 0 && item != null
                        && isFingerprint(item.getDispatchEvidenceFingerprint())
                        && item.getIssueIds() != null && !item.getIssueIds().isEmpty(),
                "MES下达单元缺少发布证据");
        List<Long> issueIds = item.getIssueIds().stream().map(value -> parseId(value, "生产计划主键"))
                .distinct().sorted().toList();
        String factoryCode = com.kitex.common.data.orgFilter.FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(factoryCode), "MES下达证据复核缺少工厂上下文");
        // 下达稍后还需写回这些计划，按主键顺序直接持写锁，避免两个发送者同时升级共享锁。
        List<PlnIssueEntity> sourceIssues = issueMapper.selectList(new LambdaQueryWrapper<PlnIssueEntity>()
                .in(PlnIssueEntity::getId, issueIds).eq(PlnIssueEntity::getFactoryCode, factoryCode)
                .orderByAsc(PlnIssueEntity::getId).last("FOR UPDATE"));
        List<PlnSchedulingTaskDetailEntity> details = taskDetailMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingTaskDetailEntity>()
                        .eq(PlnSchedulingTaskDetailEntity::getTaskId, selectedTaskId)
                        .in(PlnSchedulingTaskDetailEntity::getIssueId, issueIds)
                        .eq(PlnSchedulingTaskDetailEntity::getFactoryCode, factoryCode)
                        .orderByAsc(PlnSchedulingTaskDetailEntity::getIssueId).last("FOR SHARE"));
        List<PlnSchedulingOperationEntity> operations = operationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                        .eq(PlnSchedulingOperationEntity::getTaskId, selectedTaskId)
                        .in(PlnSchedulingOperationEntity::getIssueId, issueIds)
                        .eq(PlnSchedulingOperationEntity::getFactoryCode, factoryCode)
                        .orderByAsc(PlnSchedulingOperationEntity::getIssueId)
                        .orderByAsc(PlnSchedulingOperationEntity::getOperationSeq)
                        .orderByAsc(PlnSchedulingOperationEntity::getId).last("FOR SHARE"));
        List<PlnSchedulingBatchAllocationEntity> allocations = batchAllocationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity>()
                        .eq(PlnSchedulingBatchAllocationEntity::getTaskId, selectedTaskId)
                        .in(PlnSchedulingBatchAllocationEntity::getIssueId, issueIds)
                        .eq(PlnSchedulingBatchAllocationEntity::getFactoryCode, factoryCode)
                        .orderByAsc(PlnSchedulingBatchAllocationEntity::getBatchId)
                        .orderByAsc(PlnSchedulingBatchAllocationEntity::getAllocationSeq).last("FOR SHARE"));
        require(sourceIssues.size() == issueIds.size() && details.size() == issueIds.size()
                        && operations.size() == item.getOperationCount()
                        && operations.stream().allMatch(row -> Boolean.TRUE.equals(row.getLockStatus())
                        && !Boolean.TRUE.equals(row.getHardBlocked()))
                        && Objects.equals(dispatchEvidence(sourceIssues, details, operations, allocations),
                        item.getDispatchEvidenceFingerprint()),
                "APS发布后的计划、批次或工序证据已变化，禁止按旧Outbox下达MES");
    }

    @Override
    public List<PlnApsMesDispatchDTO.ItemReceipt> readReceipts(PlnApsMesDispatchOutboxEntity row) {
        String snapshot = row.getResponseSnapshot();
        String fingerprint = row.getResponseFingerprint();
        if (snapshot == null) {
            require(fingerprint == null && Objects.equals(row.getDispatchedCount(), 0),
                    "MES下达逐项回执缺失，请核对原单的成功记录");
            return List.of();
        }
        // 有已保存指纹时必须逐字核对。历史无指纹记录仍按原回执及成功数校验。
        require(fingerprint == null || Objects.equals(fingerprint, sha256(snapshot)),
                "MES下达回执与落账指纹不一致，请核对原始回执");
        List<PlnApsMesDispatchDTO.ItemReceipt> receipts;
        try {
            receipts = objectMapper.readValue(snapshot, new TypeReference<>() {});
        } catch (java.io.IOException ex) {
            throw new CheckedException("MES下达逐项回执无法读取，请核对原始记录");
        }
        require(receipts != null, "MES下达回执清单缺失");
        Set<String> keys = new HashSet<>();
        int succeeded = 0;
        for (var receipt : receipts) {
            require(receipt != null && StrUtil.isNotBlank(receipt.getDispatchKey())
                            && keys.add(receipt.getDispatchKey()) && StrUtil.isNotBlank(receipt.getStatus()),
                    "MES下达回执缺少单元、状态或重复登记，请核对原单");
            if ("SUCCEEDED".equals(receipt.getStatus())) {
                succeeded++;
            }
        }
        require(Objects.equals(row.getDispatchedCount(), succeeded), "MES下达已确认数量与逐项回执不一致");
        return receipts;
    }

    private PlnApsMesDispatchDTO.DispatchView toView(PlnApsMesDispatchOutboxEntity row) {
        var receipts = readReceipts(row);
        return new PlnApsMesDispatchDTO.DispatchView().setOutboxId(text(row.getId()))
                .setCaseId(text(row.getCaseId())).setScenarioId(text(row.getScenarioId()))
                .setSelectedTaskId(text(row.getSelectedTaskId())).setRequestId(row.getRequestId())
                .setRequestFingerprint(row.getRequestFingerprint()).setPublicationFingerprint(row.getPublicationFingerprint())
                .setStatus(row.getStatus()).setAttemptCount(row.getAttemptCount())
                .setDispatchedCount(row.getDispatchedCount()).setFailedCount(row.getFailedCount())
                .setNextRetryTime(row.getNextRetryTime()).setLastError(row.getLastError()).setReceipts(receipts)
                .setQueuedTime(row.getQueuedTime()).setSentTime(row.getSentTime()).setCompletedTime(row.getCompletedTime());
    }

    /**
     * 同一合批中的来源计划必须作为不可分割单元下达；拆批或跨批关联通过集合传递闭包合并。
     * 没有批次账的历史计划保持逐计划下达。
     */
    private List<List<Long>> dispatchGroups(List<PlnSchedulingTaskDetailEntity> details,
                                            List<PlnSchedulingBatchAllocationEntity> allocations) {
        List<java.util.Set<Long>> sets = new ArrayList<>();
        allocations.stream().collect(java.util.stream.Collectors.groupingBy(
                        PlnSchedulingBatchAllocationEntity::getBatchId, java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.mapping(PlnSchedulingBatchAllocationEntity::getIssueId,
                                java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new))))
                .values().forEach(set -> sets.add(new java.util.LinkedHashSet<>(set)));
        for (PlnSchedulingTaskDetailEntity detail : details) {
            if (sets.stream().noneMatch(set -> set.contains(detail.getIssueId()))) {
                sets.add(new java.util.LinkedHashSet<>(List.of(detail.getIssueId())));
            }
        }
        boolean merged;
        do {
            merged = false;
            outer:
            for (int left = 0; left < sets.size(); left++) {
                for (int right = left + 1; right < sets.size(); right++) {
                    if (sets.get(left).stream().anyMatch(sets.get(right)::contains)) {
                        sets.get(left).addAll(sets.remove(right));
                        merged = true;
                        break outer;
                    }
                }
            }
        } while (merged);
        return sets.stream().map(set -> set.stream().sorted().toList())
                .sorted(java.util.Comparator.comparing(group -> group.get(0))).toList();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new CheckedException("生成MES下达不可变载荷失败", ex);
        }
    }

    private String dispatchEvidence(List<PlnIssueEntity> sourceIssues,
                                    List<PlnSchedulingTaskDetailEntity> details,
                                    List<PlnSchedulingOperationEntity> operations,
                                    List<PlnSchedulingBatchAllocationEntity> allocations) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("sourceIssues", sourceIssues.stream().map(this::issueEvidence).toList());
        evidence.put("taskDetails", details);
        evidence.put("operations", operations);
        evidence.put("batchAllocations", allocations);
        return sha256(json(evidence));
    }

    /**
     * 冻结影响初始排程下达的业务字段，排除 MES 接收后必然变化的状态、回执和审计时间。
     * 这样工作器在“MES已接收、Outbox回执尚未落账”的崩溃窗口仍可用永久请求号取回原回执。
     */
    private Map<String, Object> issueEvidence(PlnIssueEntity issue) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", issue.getId());
        value.put("issueNo", issue.getIssueNo());
        value.put("sourceDemandNo", issue.getSourceDemandNo());
        value.put("sourceMrpId", issue.getSourceMrpId());
        value.put("planningSourceKey", issue.getPlanningSourceKey());
        value.put("mpsLineId", issue.getMpsLineId());
        value.put("productCode", issue.getProductCode());
        value.put("productName", issue.getProductName());
        value.put("specificationModel", issue.getSpecificationModel());
        value.put("materialAttribute", issue.getMaterialAttribute());
        value.put("quantity", issue.getNum());
        value.put("demandTime", issue.getDemandTime());
        value.put("scheduledDate", issue.getScheduledDate());
        value.put("scheduledEndDate", issue.getScheduledEndDate());
        value.put("lockStatus", issue.getLockStatus());
        value.put("lockTaskId", issue.getLockTaskId());
        value.put("engineeringReleaseId", issue.getEngineeringReleaseId());
        value.put("engineeringContentHash", issue.getEngineeringContentHash());
        value.put("engineeringVersionNo", issue.getEngineeringVersionNo());
        value.put("engineeringRouteId", issue.getEngineeringRouteId());
        value.put("engineeringRouteCode", issue.getEngineeringRouteCode());
        value.put("engineeringBomId", issue.getEngineeringBomId());
        value.put("engineeringBomVersion", issue.getEngineeringBomVersion());
        value.put("factoryCode", issue.getFactoryCode());
        return value;
    }

    private String caseDispatchStatus(String outboxStatus) {
        return switch (outboxStatus) {
            case "SUCCEEDED" -> "SUCCEEDED";
            case "PARTIAL" -> "PARTIAL";
            case "RETRY_WAIT" -> "RETRY_WAIT";
            case "MANUAL_REVIEW" -> "MANUAL_REVIEW";
            case "DEAD_LETTER" -> "FAILED";
            case "PENDING" -> "PENDING";
            default -> "DISPATCHING";
        };
    }
}
