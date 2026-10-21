package com.kitex.mom.mes.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesTaskReportReverseVO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.util.MesReportReverseNodeEvidence;
import com.kitex.mom.mes.util.MesFrozenExecutionGraph;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 动态外码完整逆向协调器：原正向账/成功命令不动，只追加唯一原操作的补偿证据。
 *
 * <p>这是跨领域事务协调而非实体CRUD服务。实际工单锁由审批/worker先取得，再在库存
 * 取证或写入前锁排序标识键和精确实例。预览只读；完整事实由既有申请指纹冻结。
 * 原DAG后序、原占用全部后续复用（含已释放/已撤回）及跨工单物理归属不确定均故障关闭。</p>
 */
@Service
@RequiredArgsConstructor
public class MesIdentifierBindingInverseCoordinator {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final MesActualFactoryAccessService factoryAccess;
    private final MesIdentifierBindingMapper bindings;
    private final MesIdentifierBindingGroupMapper groups;
    private final MesIdentifierBindingOperationMapper operations;
    private final MesTaskReportMapper reports;
    private final MesReportCommandMapper commands;
    private final MesReportReverseRequestMapper requests;
    private final MesReportReverseScopeMapper scopes;
    private final MesWarehouseInoutMapper stockFlows;
    private final MesWipTransferMapper transfers;
    private final MesStationPassRecordMapper stationPasses;
    private final MesTaskArrangeMapper arrangements;
    private final MesExecutionNodeReader executionNodes;

    /**
     * 工单业务锁后、原报告/库存行锁前一次取得同SN本工单全部区段标识键。
     * 链中可能横跨普通节点和多个冻结组，不能执行到下一组才在持有库存行锁时晚取键。
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void lockForReport(MesTaskReportEntity report) {
        String factory = factoryAccess.require("write");
        require(report != null && Objects.equals(report.getTenantId(), tenant())
                && Objects.equals(report.getFactoryCode(), factory), "补偿锁依据不是当前实际租户工厂");
        if (report.getTransferCode() == null || report.getTransferCode().isBlank()) return;
        List<MesIdentifierBindingEntity> rows = bindings.selectList(bindingScope(factory)
                .eq(MesIdentifierBindingEntity::getTaskCode, report.getTaskCode())
                .eq(MesIdentifierBindingEntity::getCanonicalIdentifier, report.getTransferCode()).last("LIMIT 5001"));
        require(rows.size() <= 5000, "同SN原区段超过完整锁定上限，禁止截断");
        rows.stream().flatMap(row -> List.of(row.getExternalCode(), row.getCanonicalIdentifier()).stream())
                .map(code -> MesIdentifierBindingLock.key(tenant(), factory, code)).distinct().sorted()
                .forEach(key -> require(Integer.valueOf(1).equals(bindings.lockIdentity(key)), "未取得原标识补偿锁"));
    }

    /**
     * 只在真实动态事实分支生成新版取证。普通NONE返回null，不改变旧审批摘要的字段/顺序。
     * 依赖返回原报工ID，由申请范围判定是否已完整纳入；worker逐步执行时必须全部撤回。
     */
    public Evidence evidence(MesTaskReportEntity report, boolean lock) {
        boolean dynamic = report.getIdentifierBindingId() != null;
        if (!dynamic) {
            require(report.getIdentifierBindingVersion() == null && report.getIdentifierNodeAction() == null
                    && report.getIdentifierBindingSnapshot() == null, "原动态事实不完整，禁止降级普通纠正");
            return null;
        }
        String factory = factoryAccess.require(lock ? "write" : "read");
        require(Objects.equals(report.getTenantId(), tenant()) && Objects.equals(report.getFactoryCode(), factory)
                && report.getIdentifierBindingVersion() != null && report.getIdentifierBindingSnapshot() != null
                && Set.of("BIND", "USE", "RELEASE").contains(Objects.toString(report.getIdentifierNodeAction(), ""))
                && "ONE_CODE".equals(report.getReportMode()) && "NORMAL".equals(report.getExecutionKind())
                && Integer.valueOf(1).equals(report.getIdentifierRouteVersion()), "原动态节点归属/冻结事实损坏，须人工核定");
        MesIdentifierBindingEntity observed = binding(report.getIdentifierBindingId(), factory, false);
        if (lock) {
            lockKeys(factory, observed);
        }
        final MesIdentifierBindingEntity binding = lock ? binding(observed.getId(), factory, true) : observed;
        require(Objects.equals(binding.getTaskCode(), report.getTaskCode())
                && Objects.equals(binding.getCanonicalIdentifier(), report.getTransferCode()), "原报工和占用实例的实际产品不一致");
        MesIdentifierBindingGroupEntity group = groups.selectOne(new LambdaQueryWrapper<MesIdentifierBindingGroupEntity>()
                .eq(MesIdentifierBindingGroupEntity::getTenantId, tenant())
                .eq(MesIdentifierBindingGroupEntity::getFactoryCode, factory)
                .eq(MesIdentifierBindingGroupEntity::getId, binding.getBindingGroupId()));
        require(group != null && Objects.equals(group.getTaskId(), binding.getTaskId())
                && Objects.equals(group.getTaskCode(), binding.getTaskCode()), "原永久区段缺失或换挂");
        List<MesIdentifierBindingOperationEntity> chain = operations.selectList(opScope(factory)
                .eq(MesIdentifierBindingOperationEntity::getBindingId, binding.getId())
                .orderByAsc(MesIdentifierBindingOperationEntity::getId).last("LIMIT 5001"));
        require(chain.size() <= 5000, "原节点账超过完整取证上限，禁止截断补偿");
        List<MesIdentifierBindingOperationEntity> originals = chain.stream()
                .filter(row -> row.getOriginalOperationId() == null && Objects.equals(row.getReportId(), report.getId())
                        && Objects.equals(row.getAction(), report.getIdentifierNodeAction())).toList();
        require(originals.size() == 1, "未找到唯一原正向节点操作，不能从当前状态倒推");
        MesIdentifierBindingOperationEntity original = originals.get(0);
        List<MesStationPassRecordEntity> passes = stationPasses.selectList(new LambdaQueryWrapper<MesStationPassRecordEntity>()
                .eq(MesStationPassRecordEntity::getTenantId, tenant()).eq(MesStationPassRecordEntity::getFactoryCode, factory)
                .eq(MesStationPassRecordEntity::getReportId, report.getId()).last("LIMIT 2"));
        require(passes.size() == 1 && "PASSED".equals(passes.get(0).getResult())
                && Objects.equals(passes.get(0).getRequestId(), report.getRequestId()), "原动态报工缺少唯一原过站判定账，不能补造过站逆向");
        MesReportCommandEntity command = commands.selectOne(new LambdaQueryWrapper<MesReportCommandEntity>()
                .eq(MesReportCommandEntity::getTenantId, tenant()).eq(MesReportCommandEntity::getFactoryCode, factory)
                .eq(MesReportCommandEntity::getReportId, report.getId()));
        require(command != null && "MES_REPORT_COMMAND_V2".equals(command.getCommandVersion())
                && Objects.equals(original.getRequestFingerprint(), command.getRequestFingerprint())
                && Objects.equals(original.getRequestId(), command.getRequestId())
                && Objects.equals(original.getEvidenceSnapshot(), report.getIdentifierBindingSnapshot())
                && Objects.equals(original.getVersionBefore() == 0 ? 1 : original.getVersionBefore(), report.getIdentifierBindingVersion()),
                "原节点账/报工/原正文不一致，禁止补造逆向依据");
        Set<Long> reversed = new HashSet<>();
        chain.stream().map(MesIdentifierBindingOperationEntity::getOriginalOperationId)
                .filter(Objects::nonNull).forEach(reversed::add);
        List<String> blockers = new ArrayList<>();
        if (reversed.contains(original.getId())) blockers.add("原节点操作已完整补偿，不能重复申请");
        if ("REVERSED".equals(binding.getStatus())) blockers.add("原占用实例已撤回，不能重新激活旧绑定");
        Set<String> downstreamTasks = downstream(group, original, report);
        List<MesTaskReportEntity> physicalReports = reports.selectList(new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getTenantId, tenant()).eq(MesTaskReportEntity::getFactoryCode, factory)
                .eq(MesTaskReportEntity::getTransferCode, binding.getCanonicalIdentifier())
                .orderByAsc(MesTaskReportEntity::getId).last("LIMIT 5001"));
        require(physicalReports.size() <= 5000, "同SN事实超过完整核对上限，须治理后复核");
        Set<Long> dependencies = new LinkedHashSet<>();
        for (MesTaskReportEntity fact : physicalReports) {
            if (Objects.equals(fact.getId(), report.getId()) || "REVERSED".equals(fact.getStatus())) continue;
            if (!Objects.equals(fact.getTaskCode(), report.getTaskCode())) {
                blockers.add("同SN在其他工单仍有事实，不能把另一工单猜作本DAG后序：" + fact.getId());
            } else if (downstreamTasks.contains(fact.getProduceTaskCode())) {
                if (!"EFFECTIVE".equals(fact.getStatus())) blockers.add("真实后序存在未治理历史状态：" + fact.getId());
                else dependencies.add(fact.getId());
            }
        }
        for (MesIdentifierBindingOperationEntity fact : chain) {
            if (fact.getOriginalOperationId() != null || Objects.equals(fact.getId(), original.getId()) || reversed.contains(fact.getId())) continue;
            boolean dependent = "BIND".equals(original.getAction())
                    || ("USE".equals(original.getAction()) && "RELEASE".equals(fact.getAction()));
            if (dependent) dependencies.add(fact.getReportId());
        }
        // 按原库存来源取全部去向，不只查billId=本报工，跨工单耗用也须进入冻结事实。
        List<MesWarehouseInoutEntity> sourceFlows = stockFlows.selectList(new LambdaQueryWrapper<MesWarehouseInoutEntity>()
                .eq(MesWarehouseInoutEntity::getTenantId, tenant()).eq(MesWarehouseInoutEntity::getFactoryCode, factory)
                .eq(MesWarehouseInoutEntity::getSourceReportId, report.getId())
                .orderByAsc(MesWarehouseInoutEntity::getId).last("LIMIT 5001"));
        List<MesWipTransferEntity> movements = transfers.selectList(new LambdaQueryWrapper<MesWipTransferEntity>()
                .eq(MesWipTransferEntity::getTenantId, tenant()).eq(MesWipTransferEntity::getFactoryCode, factory)
                .eq(MesWipTransferEntity::getSourceReportId, report.getId())
                .orderByAsc(MesWipTransferEntity::getId).last("LIMIT 5001"));
        require(sourceFlows.size() <= 5000 && movements.size() <= 5000, "实际库存去向/WIP历史超过完整取证上限，禁止截断");
        Set<Long> consumerIds = sourceFlows.stream().map(MesWarehouseInoutEntity::getBillId)
                .filter(id -> id != null && !Objects.equals(id, report.getId())).collect(java.util.stream.Collectors.toSet());
        List<MesTaskReportEntity> consumers = consumerIds.isEmpty() ? List.of() : reports.selectList(
                new LambdaQueryWrapper<MesTaskReportEntity>().eq(MesTaskReportEntity::getTenantId, tenant())
                        .eq(MesTaskReportEntity::getFactoryCode, factory).in(MesTaskReportEntity::getId, consumerIds));
        for (MesTaskReportEntity consumer : consumers) {
            if (consumer != null && !"REVERSED".equals(consumer.getStatus())) {
                if (Objects.equals(consumer.getTaskCode(), report.getTaskCode())
                        && downstreamTasks.contains(consumer.getProduceTaskCode()) && "EFFECTIVE".equals(consumer.getStatus())) {
                    dependencies.add(consumer.getId());
                } else blockers.add("原产出已被非本DAG报工使用，须先核对实际来源链：" + consumer.getId());
            }
        }
        List<MesIdentifierBindingEntity> histories = bindings.selectList(bindingScope(factory)
                .and(q -> q.eq(MesIdentifierBindingEntity::getCanonicalIdentifier, binding.getCanonicalIdentifier())
                        .or(n -> n.eq(MesIdentifierBindingEntity::getExternalNamespace, binding.getExternalNamespace())
                                .eq(MesIdentifierBindingEntity::getExternalCode, binding.getExternalCode())))
                .orderByAsc(MesIdentifierBindingEntity::getId).last("LIMIT 5001"));
        require(histories.size() <= 5000, "外码/SN占用历史超过完整核对上限，禁止只核对活动行");
        if ("RELEASE".equals(original.getAction())) {
            if (!"RELEASED".equals(binding.getStatus()) || !Objects.equals(binding.getReleaseReportId(), report.getId())
                    || !Objects.equals(binding.getVersionNo(), original.getVersionAfter())) {
                blockers.add("释放操作不是当前生命周期终点，禁止恢复另一次占用");
            }
            for (MesIdentifierBindingEntity history : histories) {
                if (Objects.equals(history.getId(), binding.getId())) continue;
                if (binding.getOccupationSequence() == null || (history.getOccupationSequence() != null
                        && history.getOccupationSequence() > binding.getOccupationSequence())) {
                    blockers.add("释放后外码或产品存在后续占用历史/旧顺序未知，不能恢复旧实物占用：" + history.getId());
                }
            }
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("version", "MES_BINDING_REVERSE_EVIDENCE_V1");
        facts.put("reportBinding", Arrays.asList(report.getIdentifierBindingId().toString(),
                report.getIdentifierBindingVersion(), report.getIdentifierNodeAction(), report.getIdentifierBindingSnapshot()));
        facts.put("group", Arrays.asList(group.getId().toString(), group.getVersionNo(), group.getDefinitionFingerprint(),
                group.getGraphFingerprint(), group.getSourceRouteFingerprint(), group.getGraphSnapshot()));
        facts.put("binding", bindingFact(binding));
        facts.put("operations", chain.stream().map(this::operationFact).toList());
        MesStationPassRecordEntity pass = passes.get(0);
        facts.put("stationPass", Arrays.asList(pass.getId().toString(), pass.getRequestId(), pass.getRequestFingerprint(),
                pass.getResult(), pass.getArrangeCode(), pass.getTransferCode(), pass.getValidationSnapshot()));
        facts.put("reuseHistory", histories.stream().map(this::bindingFact).toList());
        facts.put("physicalReports", physicalReports.stream().map(row -> Arrays.asList(row.getId().toString(),
                row.getTaskCode(), row.getProduceTaskCode(), row.getArrangeCode(), row.getStatus(), row.getTransferCode(),
                row.getIdentifierBindingId() == null ? null : row.getIdentifierBindingId().toString(),
                row.getIdentifierBindingVersion(), row.getEffectiveReportNum(), row.getCorrectedReportNum(), row.getReverseRequestId())).toList());
        facts.put("dependencies", dependencies.stream().sorted().map(Object::toString).toList());
        facts.put("sourceStockFlows", sourceFlows.stream().map(row -> Arrays.asList(row.getId().toString(),
                row.getBillId(), row.getSourceStockId(), row.getSourceReportId(), row.getOperateType(), row.getBizType(),
                row.getOperateNum(), row.getStockBeforeNum(), row.getStockAfterNum(), row.getStockProofVersion())).toList());
        facts.put("wipMovements", movements.stream().map(row -> Arrays.asList(row.getId().toString(), row.getStatus(),
                row.getLostQuantity(), row.getSourceReportId(), row.getUpdateTime())).toList());
        return new Evidence(binding, original, Collections.unmodifiableMap(facts), List.copyOf(blockers), Set.copyOf(dependencies));
    }

    /** 原报工已锁后、任何库存写入前执行；必须仍有本次异岗复核资格和精确冻结scope。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public Prepared prepare(MesTaskReportEntity report, MesTaskReportReverseVO command) {
        Evidence evidence = evidence(report, true);
        if (evidence == null) return null;
        MesReportReverseExecutionAuthority authority = MesReportReverseExecutionAuthority.requireCurrent();
        String factory = factoryAccess.require("write");
        require(Objects.equals(authority.requestId, command.getReverseRequestBusinessId())
                && Objects.equals(authority.evidenceFingerprint, command.getEvidenceFingerprint()), "补偿命令与本次复核资格不符");
        MesReportReverseRequestEntity request = requests.selectOne(new LambdaQueryWrapper<MesReportReverseRequestEntity>()
                .eq(MesReportReverseRequestEntity::getTenantId, tenant())
                .eq(MesReportReverseRequestEntity::getFactoryCode, factory).eq(MesReportReverseRequestEntity::getId, authority.requestId));
        MesReportReverseScopeEntity scope = scopes.selectOne(new LambdaQueryWrapper<MesReportReverseScopeEntity>()
                .eq(MesReportReverseScopeEntity::getTenantId, tenant()).eq(MesReportReverseScopeEntity::getFactoryCode, factory)
                .eq(MesReportReverseScopeEntity::getReverseRequestId, authority.requestId)
                .eq(MesReportReverseScopeEntity::getReportId, report.getId()));
        require(request != null && "SUBMITTED".equals(request.getStatus()) && scope != null && Boolean.TRUE.equals(scope.getActive())
                && Objects.equals(request.getEvidenceFingerprint(), authority.evidenceFingerprint)
                && authority.scopes.containsKey(report.getId())
                && Objects.equals(scope.getId(), authority.scopes.get(report.getId()).getId())
                && Objects.equals(scope.getChildReverseRequestId(), command.getRequestId())
                && !Objects.equals(request.getApplicantId(), authority.reviewerId)
                && !Objects.equals(request.getApplicantCode(), authority.reviewerCode), "缺少本次异岗复核的原申请/逐报工范围/原号");
        String approvedSnapshot = approvedScopeSnapshot(authority, report.getId());
        require(Objects.equals(sha256(approvedSnapshot), scope.getEvidenceFingerprint()), "逐报告批准事实与原scope摘要不一致");
        require(evidence.blockers().isEmpty(), String.join("；", evidence.blockers()));
        require(evidence.dependencies().isEmpty(), "原节点仍有有效后序/使用/释放事实，必须按真实DAG先撤回：" + evidence.dependencies());
        MesIdentifierBindingEntity binding = evidence.binding();
        int before = Objects.requireNonNull(binding.getVersionNo(), "占用版本");
        boolean release = "RELEASE".equals(evidence.original().getAction());
        require(before > 0 && before < Integer.MAX_VALUE
                && (release ? "RELEASED".equals(binding.getStatus()) && before % 2 == 0
                : "ACTIVE".equals(binding.getStatus()) && before % 2 == 1), "原占用当前状态不能执行此补偿");
        int after = "USE".equals(evidence.original().getAction()) ? before : before + 1;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", "MES_BINDING_INVERSE_V1");
        body.put("tenantId", tenant().toString());
        body.put("factoryCode", factory);
        body.put("requestId", command.getRequestId());
        body.put("reportId", report.getId().toString());
        body.put("bindingId", binding.getId().toString());
        body.put("originalOperationId", evidence.original().getId().toString());
        body.put("originalRequestFingerprint", evidence.original().getRequestFingerprint());
        body.put("reverseRequestId", authority.requestId.toString());
        body.put("approvalEvidenceFingerprint", authority.evidenceFingerprint);
        body.put("approvalScopeFingerprint", scope.getEvidenceFingerprint());
        body.put("approvalScopeSnapshot", approvedSnapshot);
        body.put("reviewRequestId", authority.reviewRequestId);
        body.put("reviewFingerprint", authority.reviewFingerprint);
        body.put("actorId", authority.reviewerId.toString());
        body.put("actorCode", authority.reviewerCode);
        body.put("reason", command.getReason().trim());
        body.put("action", "REVERSE_" + evidence.original().getAction());
        body.put("versionBefore", before);
        body.put("versionAfter", after);
        body.put("evidence", evidence.facts());
        String json = JSONUtil.toJsonStr(body);
        require(json.getBytes(StandardCharsets.UTF_8).length <= 32 * 1024 * 1024,
                "完整节点补偿正文超过32MiB安全核对上限，禁止截断或继续写库存");
        return new Prepared(IdWorker.getId(), evidence, command.getRequestId(), authority.requestId,
                authority.evidenceFingerprint, json, sha256("MES_BINDING_INVERSE_V1:" + json), before, after);
    }

    /**
     * 原库存/产量逆向成功后、标记原报告REVERSED前追加；两者同事务提交、任一失败全部回滚。
     * BEFORE报告守卫要求先存在补偿账；延迟守卫要求最终REVERSED和APPROVED真实履历，不能只写其中之一。
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void complete(Prepared prepared) {
        if (prepared == null) return;
        MesReportReverseExecutionAuthority authority = MesReportReverseExecutionAuthority.requireCurrent();
        require(Objects.equals(authority.requestId, prepared.reverseRequestId()), "补偿事务资格已丢失");
        Evidence evidence = prepared.evidence();
        MesIdentifierBindingEntity binding = evidence.binding();
        String action = evidence.original().getAction();
        LocalDateTime now = LocalDateTime.now();
        if (!"USE".equals(action)) {
            require(bindings.update(null, new LambdaUpdateWrapper<MesIdentifierBindingEntity>()
                    .eq(MesIdentifierBindingEntity::getTenantId, tenant())
                    .eq(MesIdentifierBindingEntity::getFactoryCode, binding.getFactoryCode())
                    .eq(MesIdentifierBindingEntity::getId, binding.getId())
                    .eq(MesIdentifierBindingEntity::getStatus, binding.getStatus())
                    .eq(MesIdentifierBindingEntity::getVersionNo, prepared.versionBefore())
                    .set(MesIdentifierBindingEntity::getStatus, "RELEASE".equals(action) ? "ACTIVE" : "REVERSED")
                    .set(MesIdentifierBindingEntity::getVersionNo, prepared.versionAfter())
                    .set(MesIdentifierBindingEntity::getLastInverseOperationId, prepared.operationId())
                    .set(MesIdentifierBindingEntity::getUpdateBy, authority.reviewerCode)
                    .set(MesIdentifierBindingEntity::getUpdateTime, now)) == 1, "原占用已变化，补偿及库存全部回滚");
        }
        MesIdentifierBindingOperationEntity original = evidence.original();
        MesIdentifierBindingOperationEntity inverse = new MesIdentifierBindingOperationEntity()
                .setFactoryCode(binding.getFactoryCode()).setBindingId(binding.getId()).setBindingGroupId(binding.getBindingGroupId())
                .setReportId(original.getReportId()).setRequestId(prepared.requestId())
                .setCommandVersion(original.getCommandVersion()).setRequestFingerprint(original.getRequestFingerprint())
                .setAction("REVERSE_" + action).setArrangeCode(original.getArrangeCode()).setRouteCode(original.getRouteCode())
                .setVersionBefore(prepared.versionBefore()).setVersionAfter(prepared.versionAfter())
                .setEvidenceSnapshot(JSONUtil.toJsonStr(evidence.facts())).setOriginalOperationId(original.getId())
                .setReverseRequestId(prepared.reverseRequestId()).setApprovalEvidenceFingerprint(prepared.approvalFingerprint())
                .setInverseCommandSnapshot(prepared.commandSnapshot()).setInverseCommandFingerprint(prepared.commandFingerprint())
                .setActorId(authority.reviewerId).setActorCode(authority.reviewerCode).setOperationTime(now);
        inverse.setId(prepared.operationId());
        inverse.setTenantId(tenant());
        inverse.setCreateBy(authority.reviewerCode);
        inverse.setCreateTime(now);
        inverse.setDelFlag("0");
        require(operations.insert(inverse) == 1, "不可变补偿账未保存，库存/产量/占用整笔回滚");
    }

    /** 使用原永久完整DAG的全部后序，不用routeOrder切片或只看最后一笔操作。 */
    private Set<String> downstream(MesIdentifierBindingGroupEntity group,
                                   MesIdentifierBindingOperationEntity original, MesTaskReportEntity report) {
        boolean nativeNodes = MesIdentifierNodePolicyValidator.NODE_BINDING_V2.equals(group.getPolicySchemaVersion());
        require(nativeNodes || group.getPolicySchemaVersion() == null, "未知永久区段内容协议");
        require(nativeNodes == isNativeBinding(report), "原报告绑定证据与永久组协议不一致");
        String route = original.getRouteCode();
        Set<String> actualDownstream = null;
        if (nativeNodes) {
            JsonNode evidence = bindingSnapshot(report);
            List<MesTaskArrangeEntity> matches = arrangements.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                    .eq(MesTaskArrangeEntity::getTenantId, report.getTenantId())
                    .eq(MesTaskArrangeEntity::getFactoryCode, report.getFactoryCode())
                    .eq(MesTaskArrangeEntity::getCode, report.getArrangeCode()).last("LIMIT 2"));
            require(matches.size() == 1, "原动态报告未唯一归属实际派工");
            MesTaskArrangeEntity arrange = matches.get(0);
            MesReportReverseNodeEvidence.assertReport(report, arrange);
            var actualGraph = executionNodes.readGraphs(report.getTenantId(), report.getFactoryCode(), List.of(report.getTaskCode()))
                    .get(report.getTaskCode());
            var actual = actualGraph.current(arrange);
            require(MesFrozenExecutionGraph.NODE_EXECUTION_V1.equals(actual.getRouteExecutionSchemaVersion())
                    && Objects.equals(group.getProcessRouteCode(), actual.getProcessRouteCode())
                    && Objects.equals(evidence.path("produceTaskCode").asText(), actual.getCode())
                    && Objects.equals(evidence.path("sourceRouteNodeCode").asText(), actual.getSourceRouteNodeCode())
                    && Objects.equals(evidence.path("routeCode").asText(), actual.getRouteCode())
                    && Objects.equals(original.getRouteCode(), actual.getRouteCode())
                    && Objects.equals(original.getArrangeCode(), arrange.getCode())
                    && MesIdentifierNodePolicyValidator.NODE_BINDING_V2.equals(evidence.path("policySchemaVersion").asText())
                    && MesFrozenExecutionGraph.NODE_EXECUTION_V1.equals(evidence.path("routeExecutionSchemaVersion").asText()),
                    "原动态操作、报告、实际任务和节点证据不一致");
            route = actual.getSourceRouteNodeCode();
            actualDownstream = actualGraph.descendantDepths(actual).keySet();
        }
        JsonNode graph;
        try { graph = JSON.readTree(group.getGraphSnapshot()); }
        catch (Exception exception) { throw new CheckedException("原永久DAG损坏，禁止补偿"); }
        require(graph.isArray() && graph.size() <= 2000, "原永久完整DAG格式或规模错误");
        Map<String, JsonNode> nodes = new HashMap<>();
        String identityField = nativeNodes ? "sourceRouteNodeCode" : "routeCode";
        String nextField = nativeNodes ? "successorNodeCodes" : "successorCodes";
        for (JsonNode node : graph) {
            require(node.path(identityField).isTextual() && node.path(nextField).isArray()
                    && nodes.put(node.path(identityField).asText(), node) == null, "原永久DAG有重复/无效节点");
        }
        require(nodes.containsKey(route), "原操作不在永久DAG内");
        require(!nativeNodes || Objects.equals(nodes.get(route).path("produceTaskCode").asText(), report.getProduceTaskCode()),
                "原操作实际任务与永久完整图不一致");
        Deque<String> pending = new ArrayDeque<>();
        nodes.get(route).path(nextField).forEach(code -> pending.add(code.asText()));
        Set<String> seen = new HashSet<>();
        Set<String> taskCodes = new HashSet<>();
        while (!pending.isEmpty()) {
            String next = pending.removeFirst();
            require(!route.equals(next) && nodes.containsKey(next), "原永久DAG存在循环/缺失引用");
            if (!seen.add(next)) continue;
            taskCodes.add(nodes.get(next).path("produceTaskCode").asText());
            nodes.get(next).path(nextField).forEach(code -> pending.add(code.asText()));
        }
        require(!nativeNodes || taskCodes.equals(actualDownstream), "永久区段后裔与原完整实际任务图不一致");
        return Set.copyOf(taskCodes);
    }

    /** 只读已存报告版本，不受客户端或当前模板选择影响。 */
    public static boolean isNativeBinding(MesTaskReportEntity report) {
        if (report.getIdentifierBindingId() == null) return false;
        String version = bindingSnapshot(report).path("version").asText();
        require(Set.of("MES_NODE_BINDING_V1", "MES_NODE_BINDING_NODE_V2").contains(version), "未知原动态报工证据协议");
        return "MES_NODE_BINDING_NODE_V2".equals(version);
    }

    private static JsonNode bindingSnapshot(MesTaskReportEntity report) {
        try {
            JsonNode result = JSON.readTree(report.getIdentifierBindingSnapshot());
            require(result != null && result.isObject(), "原动态证据缺失");
            return result;
        } catch (Exception exception) {
            throw new CheckedException("原动态报工证据损坏，不能重解释或降级");
        }
    }

    private List<Object> bindingFact(MesIdentifierBindingEntity binding) {
        return Arrays.asList(binding.getId().toString(), binding.getBindingGroupId().toString(), binding.getTaskCode(),
                binding.getExternalNamespace(), binding.getExternalCode(), binding.getCanonicalIdentifier(),
                binding.getStatus(), binding.getVersionNo(), binding.getOccupationSequence() == null ? null : binding.getOccupationSequence().toString(),
                binding.getBindReportId().toString(), binding.getReleaseReportId() == null ? null : binding.getReleaseReportId().toString(),
                binding.getLastInverseOperationId() == null ? null : binding.getLastInverseOperationId().toString());
    }

    /** 取原申请永久保存的完整逐报告事实，而非用本步已变化的实例/库存倒写批准依据。 */
    private String approvedScopeSnapshot(MesReportReverseExecutionAuthority authority, Long reportId) {
        JsonNode snapshot;
        try { snapshot = JSON.readTree(authority.evidenceSnapshot); }
        catch (Exception exception) { throw new CheckedException("动态原申请完整冻结JSON损坏"); }
        require(Objects.equals(sha256(authority.evidenceSnapshot), authority.evidenceFingerprint)
                && Set.of(MesReportReverseNodeEvidence.BINDING_VERSION, MesReportReverseNodeEvidence.BINDING_NODE_VERSION)
                .contains(snapshot.path("version").asText())
                && snapshot.path("bindingScopeSnapshots").isArray(), "动态原申请没有可核验的完整逐报告事实");
        List<String> matches = new ArrayList<>();
        for (JsonNode item : snapshot.path("bindingScopeSnapshots")) {
            if (reportId.toString().equals(item.path("reportId").asText()) && item.path("evidenceSnapshot").isTextual()) {
                matches.add(item.path("evidenceSnapshot").asText());
            }
        }
        require(matches.size() == 1, "原申请未唯一保存本报告完整事实，不能从当前事实补造批准依据");
        return matches.get(0);
    }

    private List<Object> operationFact(MesIdentifierBindingOperationEntity row) {
        return Arrays.asList(row.getId().toString(), row.getReportId().toString(), row.getRequestId(), row.getAction(),
                row.getRouteCode(), row.getVersionBefore(), row.getVersionAfter(), row.getRequestFingerprint(), row.getEvidenceSnapshot(),
                row.getOriginalOperationId() == null ? null : row.getOriginalOperationId().toString(),
                row.getReverseRequestId() == null ? null : row.getReverseRequestId().toString(), row.getInverseCommandFingerprint());
    }

    private MesIdentifierBindingEntity binding(Long id, String factory, boolean lock) {
        MesIdentifierBindingEntity binding = bindings.selectOne(bindingScope(factory)
                .eq(MesIdentifierBindingEntity::getId, id).last(lock ? "FOR UPDATE" : ""));
        require(binding != null, "原占用实例不属于当前租户工厂");
        return binding;
    }

    private void lockKeys(String factory, MesIdentifierBindingEntity binding) {
        List.of(binding.getExternalCode(), binding.getCanonicalIdentifier()).stream()
                .map(code -> MesIdentifierBindingLock.key(tenant(), factory, code)).distinct().sorted()
                .forEach(key -> require(Integer.valueOf(1).equals(bindings.lockIdentity(key)), "未取得原标识补偿锁"));
    }

    private LambdaQueryWrapper<MesIdentifierBindingEntity> bindingScope(String factory) {
        return new LambdaQueryWrapper<MesIdentifierBindingEntity>().eq(MesIdentifierBindingEntity::getTenantId, tenant())
                .eq(MesIdentifierBindingEntity::getFactoryCode, factory);
    }

    private LambdaQueryWrapper<MesIdentifierBindingOperationEntity> opScope(String factory) {
        return new LambdaQueryWrapper<MesIdentifierBindingOperationEntity>().eq(MesIdentifierBindingOperationEntity::getTenantId, tenant())
                .eq(MesIdentifierBindingOperationEntity::getFactoryCode, factory);
    }

    private Long tenant() { return TenantContextHolder.getTenantId(); }

    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new CheckedException("无法生成补偿命令摘要"); }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new CheckedException(message); }

    /** 只读原事实、不能覆盖的原操作及真实后序依赖。 */
    public record Evidence(MesIdentifierBindingEntity binding, MesIdentifierBindingOperationEntity original,
                           Map<String, Object> facts, List<String> blockers, Set<Long> dependencies) { }

    /** 本次事务的精确准备结果；不可拿到其他请求独立执行。 */
    public record Prepared(Long operationId, Evidence evidence, String requestId, Long reverseRequestId,
                           String approvalFingerprint, String commandSnapshot, String commandFingerprint,
                           int versionBefore, int versionAfter) { }
}
