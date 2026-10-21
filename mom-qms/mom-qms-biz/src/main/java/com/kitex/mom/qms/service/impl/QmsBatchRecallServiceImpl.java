package com.kitex.mom.qms.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.agent.dto.MesBatchTraceDTO;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.qms.agent.dto.QmsBatchQualityTraceDTO;
import com.kitex.mom.qms.agent.service.QmsAgentFactoryAccessService;
import com.kitex.mom.qms.agent.service.QmsBatchQualityTraceQueryService;
import com.kitex.mom.qms.dto.QmsBatchRecallDTO;
import com.kitex.mom.qms.entity.QmsBatchRecallActionEntity;
import com.kitex.mom.qms.entity.QmsBatchRecallEntity;
import com.kitex.mom.qms.entity.QmsBatchRecallOperationEntity;
import com.kitex.mom.qms.mapper.QmsBatchRecallActionMapper;
import com.kitex.mom.qms.mapper.QmsBatchRecallMapper;
import com.kitex.mom.qms.mapper.QmsBatchRecallOperationMapper;
import com.kitex.mom.qms.service.QmsBatchRecallService;
import com.kitex.mom.wms.agent.dto.WmsBatchStockTraceDTO;
import com.kitex.mom.wms.api.feign.RemoteLiveStockService;
import com.kitex.mom.wms.api.feign.RemoteWmsAgentQueryService;
import com.kitex.mom.wms.dto.WmsQualityStatusCommand;
import com.kitex.mom.wms.dto.WmsQualityStatusResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 批次召回候选治理实现。
 *
 * <p>申请与批准只冻结事实，不直接改业务数据；库存隔离始终调用WMS正式幂等命令。
 * 当前尚无客户/销售召回接口，因此客户影响只能通过有编号的人工通知证据闭环，系统不会伪造自动通知成功。</p>
 */
@Service
@RequiredArgsConstructor
public class QmsBatchRecallServiceImpl extends ServiceImpl<QmsBatchRecallMapper, QmsBatchRecallEntity>
        implements QmsBatchRecallService {

    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> EXECUTABLE_STATES = Set.of(
            "APPROVED", "EXECUTING", "RECONCILE_REQUIRED", "WAITING_NOTIFICATION", "WAITING_VERIFICATION");

    private final QmsBatchRecallActionMapper actionMapper;
    private final QmsBatchRecallOperationMapper operationMapper;
    private final QmsAgentFactoryAccessService factoryAccess;
    private final QmsBatchQualityTraceQueryService qualityTraceService;
    private final RemoteMesTaskService mesTaskService;
    private final RemoteWmsAgentQueryService wmsAgentQueryService;
    private final RemoteLiveStockService liveStockService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QmsBatchRecallEntity propose(QmsBatchRecallDTO.ProposeCommand command) {
        require(command != null, "召回申请不能为空");
        normalize(command);
        String factory = factoryAccess.requireRead(currentFactory());
        QmsBatchRecallEntity replay = getOne(new LambdaQueryWrapper<QmsBatchRecallEntity>()
                .eq(QmsBatchRecallEntity::getRequestId, command.getRequestId()), false);
        if (replay != null) {
            factoryAccess.requireRead(replay.getFactoryCode());
            require(Objects.equals(replay.getMaterialCode(), command.getMaterialCode())
                            && Objects.equals(replay.getBatchNo(), command.getBatchNo())
                            && Objects.equals(replay.getSeverity(), command.getSeverity())
                            && Objects.equals(replay.getReason(), command.getReason()),
                    "召回申请请求号已被其他内容占用");
            return replay;
        }
        EvidenceBundle evidence = evidence(factory, command.getMaterialCode(), command.getBatchNo());
        assertCandidateActionable(evidence);
        Actor actor = actor();
        QmsBatchRecallEntity recall = new QmsBatchRecallEntity()
                .setRecallNo("RC-" + IdUtil.getSnowflakeNextIdStr())
                .setRequestId(command.getRequestId()).setMaterialCode(command.getMaterialCode())
                .setBatchNo(command.getBatchNo()).setSeverity(command.getSeverity()).setReason(command.getReason())
                .setStatus("PENDING_REVIEW").setProposedBy(actor.code()).setProposedByName(actor.name())
                .setProposedTime(LocalDateTime.now()).setFactoryCode(factory);
        applyEvidence(recall, evidence);
        require(save(recall), "召回候选保存失败");
        saveOperation(command.getRequestId(), recall, null, "PROPOSE", null, recall.getStatus(),
                "冻结跨域证据=" + recall.getEvidenceHash());
        return recall;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QmsBatchRecallEntity review(QmsBatchRecallDTO.ReviewCommand command) {
        require(command != null && command.getRecallId() != null, "召回复核命令不能为空");
        normalizeRequest(command.getRequestId());
        command.setRequestId(command.getRequestId().trim());
        QmsBatchRecallEntity replay = replay(command.getRequestId(), command.getRecallId(), "REVIEW");
        if (replay != null) return replay;
        QmsBatchRecallEntity recall = lock(command.getRecallId());
        require("PENDING_REVIEW".equals(recall.getStatus()), "只有待复核召回候选可以审核");
        require(Objects.equals(recall.getEvidenceHash(), command.getExpectedEvidenceHash()),
                "页面证据摘要已过期，请刷新后复核");
        Actor reviewer = actor();
        require(!Objects.equals(reviewer.code(), recall.getProposedBy()), "召回申请人与复核人必须不同");
        require(StrUtil.isNotBlank(command.getRemark()), "复核意见不能为空");
        String before = recall.getStatus();
        if (!Boolean.TRUE.equals(command.getApproved())) {
            recall.setStatus("REJECTED").setReviewedBy(reviewer.code()).setReviewedByName(reviewer.name())
                    .setReviewRemark(trim(command.getRemark(), 2000)).setReviewedTime(LocalDateTime.now())
                    .setLastError(null);
            require(updateById(recall), "召回驳回结果保存失败");
            saveOperation(command.getRequestId(), recall, null, "REVIEW", before, recall.getStatus(), command.getRemark());
            return recall;
        }

        EvidenceBundle fresh = evidence(recall.getFactoryCode(), recall.getMaterialCode(), recall.getBatchNo());
        assertCandidateActionable(fresh);
        if (!Objects.equals(recall.getEvidenceHash(), fresh.evidenceHash())) {
            applyEvidence(recall, fresh);
            recall.setLastError("复核时证据已变化，候选已刷新；请基于新摘要重新复核");
            require(updateById(recall), "召回候选新证据保存失败");
            saveOperation(command.getRequestId(), recall, null, "REVIEW", before, recall.getStatus(),
                    recall.getLastError() + "，新摘要=" + recall.getEvidenceHash());
            return recall;
        }
        recall.setStatus("APPROVED").setReviewedBy(reviewer.code()).setReviewedByName(reviewer.name())
                .setReviewRemark(trim(command.getRemark(), 2000)).setReviewedTime(LocalDateTime.now())
                .setApprovedEvidenceHash(recall.getEvidenceHash()).setLastError(null);
        require(updateById(recall), "召回批准结果保存失败");
        if (Boolean.TRUE.equals(recall.getCustomerNotificationRequired())) {
            createManualCustomerScopeAction(recall);
        }
        saveOperation(command.getRequestId(), recall, null, "REVIEW", before, recall.getStatus(), command.getRemark());
        return recall;
    }

    @Override
    public QmsBatchRecallEntity execute(QmsBatchRecallDTO.ExecuteCommand command) {
        require(command != null && command.getRecallId() != null, "召回执行命令不能为空");
        normalizeRequest(command.getRequestId());
        command.setRequestId(command.getRequestId().trim());
        QmsBatchRecallEntity replay = replay(command.getRequestId(), command.getRecallId(), "EXECUTE");
        if (replay != null) return replay;
        QmsBatchRecallEntity recall = requireRecall(command.getRecallId());
        require(EXECUTABLE_STATES.contains(recall.getStatus()), "当前召回单不能执行库存隔离");
        EvidenceBundle fresh = evidence(recall.getFactoryCode(), recall.getMaterialCode(), recall.getBatchNo());
        if (!Objects.equals(recall.getDownstreamScopeHash(), fresh.downstreamScopeHash())) {
            String before = recall.getStatus();
            applyEvidence(recall, fresh);
            recall.setStatus("PENDING_REVIEW").setApprovedEvidenceHash(null).setReviewedBy(null)
                    .setReviewedByName(null).setReviewedTime(null).setReviewRemark(null)
                    .setLastError("生产或质量影响范围已变化，必须重新进行异人复核");
            require(updateById(recall), "召回影响范围漂移状态保存失败");
            saveOperation(command.getRequestId(), recall, null, "EXECUTE", before, recall.getStatus(),
                    recall.getLastError());
            return recall;
        }
        assertUnitsActionable(fresh.wms());
        createCurrentHoldActions(recall, fresh);
        List<QmsBatchRecallActionEntity> holds = actions(recall.getId(), "HOLD_STOCK");
        for (QmsBatchRecallActionEntity action : holds) {
            if (!"SUCCEEDED".equals(action.getStatus())) executeHold(recall, action);
        }
        holds = actions(recall.getId(), "HOLD_STOCK");
        boolean failed = holds.stream().anyMatch(item -> Set.of("FAILED", "UNKNOWN").contains(item.getStatus()));
        boolean pending = holds.stream().anyMatch(item -> !"SUCCEEDED".equals(item.getStatus()));
        boolean notificationDone = !Boolean.TRUE.equals(recall.getCustomerNotificationRequired())
                || customerScopeRecorded(recall.getId());
        String before = recall.getStatus();
        String after = failed ? "RECONCILE_REQUIRED"
                : pending ? "EXECUTING" : notificationDone ? "WAITING_VERIFICATION" : "WAITING_NOTIFICATION";
        Actor executor = actor();
        recall.setStatus(after).setExecutedBy(executor.code()).setExecutedByName(executor.name())
                .setExecutedTime(LocalDateTime.now())
                .setLastError(failed ? "部分WMS隔离结果未知或失败，须使用新请求号重试并核对永久回执" : null);
        require(updateById(recall), "召回执行状态保存失败");
        saveOperation(command.getRequestId(), recall, null, "EXECUTE", before, after,
                "库存隔离动作=" + holds.size() + "，失败=" + failed + "，待处理=" + pending);
        return recall;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QmsBatchRecallEntity recordNotification(QmsBatchRecallDTO.NotificationCommand command) {
        require(command != null && command.getRecallId() != null, "客户通知留痕命令不能为空");
        normalizeRequest(command.getRequestId());
        command.setRequestId(command.getRequestId().trim());
        QmsBatchRecallEntity replay = replay(command.getRequestId(), command.getRecallId(), "RECORD_NOTIFICATION");
        if (replay != null) return replay;
        QmsBatchRecallEntity recall = lock(command.getRecallId());
        require(Set.of("WAITING_NOTIFICATION", "WAITING_VERIFICATION", "RECONCILE_REQUIRED").contains(recall.getStatus()),
                "当前召回单不允许登记客户通知证据");
        require(Boolean.TRUE.equals(recall.getCustomerNotificationRequired()), "该候选没有客户范围核对要求");
        require(StrUtil.isNotBlank(command.getTargetType()) && StrUtil.isNotBlank(command.getTargetReference())
                && StrUtil.isNotBlank(command.getNotificationReference()) && StrUtil.isNotBlank(command.getEvidence())
                && StrUtil.isNotBlank(command.getRemark()), "客户通知对象、业务编号、凭证和说明均不能为空");
        Actor actor = actor();
        QmsBatchRecallActionEntity action = new QmsBatchRecallActionEntity()
                .setRecallId(recall.getId()).setActionNo("RA-" + IdUtil.getSnowflakeNextIdStr())
                .setRequestId(command.getRequestId()).setActionType("CUSTOMER_NOTIFICATION")
                .setTargetType(trim(command.getTargetType(), 60))
                .setTargetReference(trim(command.getTargetReference(), 300))
                .setRemoteReference(trim(command.getNotificationReference(), 200))
                .setEvidence(trim(command.getEvidence(), 4000)).setEvidenceHash(hash(command.getEvidence()))
                .setStatus("SUCCEEDED").setResultMessage(trim(command.getRemark(), 1000)).setAttemptCount(1)
                .setExecutedBy(actor.code()).setExecutedByName(actor.name()).setExecutedTime(LocalDateTime.now())
                .setFactoryCode(recall.getFactoryCode());
        require(actionMapper.insert(action) == 1, "客户通知证据保存失败");
        String before = recall.getStatus();
        if (allHoldActionsSucceeded(recall.getId())) recall.setStatus("WAITING_VERIFICATION");
        recall.setLastError(null);
        require(updateById(recall), "召回通知状态保存失败");
        saveOperation(command.getRequestId(), recall, action, "RECORD_NOTIFICATION", before, recall.getStatus(),
                "人工通知/范围核对编号=" + command.getNotificationReference() + "；系统未声明已调用外部客户接口");
        return recall;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QmsBatchRecallEntity verify(QmsBatchRecallDTO.VerifyCommand command) {
        require(command != null && command.getRecallId() != null, "召回验证命令不能为空");
        normalizeRequest(command.getRequestId());
        command.setRequestId(command.getRequestId().trim());
        QmsBatchRecallEntity replay = replay(command.getRequestId(), command.getRecallId(), "VERIFY");
        if (replay != null) return replay;
        QmsBatchRecallEntity recall = lock(command.getRecallId());
        require("WAITING_VERIFICATION".equals(recall.getStatus()), "只有待验证召回单可以关闭");
        require(allHoldActionsSucceeded(recall.getId()), "仍有库存隔离动作未取得成功回执");
        require(!Boolean.TRUE.equals(recall.getCustomerNotificationRequired()) || customerScopeRecorded(recall.getId()),
                "客户影响范围尚未登记正式人工通知或核对凭证");
        Actor verifier = actor();
        require(!Objects.equals(verifier.code(), recall.getProposedBy())
                        && !Objects.equals(verifier.code(), recall.getReviewedBy())
                        && !Objects.equals(verifier.code(), recall.getExecutedBy()),
                "验证人必须独立于申请、复核和库存隔离执行人");
        require(StrUtil.isNotBlank(command.getRemark()), "独立验证结论不能为空");
        EvidenceBundle fresh = evidence(recall.getFactoryCode(), recall.getMaterialCode(), recall.getBatchNo());
        require(Objects.equals(recall.getDownstreamScopeHash(), fresh.downstreamScopeHash()),
                "生产或质量影响范围已变化，不能关闭；请重新发起召回复核");
        boolean noFreezable = fresh.wms().getUnitQuantities().stream()
                .allMatch(unit -> quantity(unit.getRecallFreezableQuantity()).signum() == 0);
        require(noFreezable && Objects.equals(fresh.wms().getPositiveStockRowCount(), fresh.wms().getLockedRowCount()),
                "当前仍存在未锁定的该批次正库存，不能通过关闭验证");
        String before = recall.getStatus();
        String conclusion = Boolean.TRUE.equals(recall.getCustomerNotificationRequired())
                ? "CONTROLLED_WITH_MANUAL_CUSTOMER_SCOPE" : "INTERNAL_STOCK_CONTROLLED";
        recall.setStatus("CLOSED").setVerifiedBy(verifier.code()).setVerifiedByName(verifier.name())
                .setVerificationRemark(trim(command.getRemark(), 2000)).setVerificationConclusion(conclusion)
                .setVerifiedTime(LocalDateTime.now()).setClosedTime(LocalDateTime.now()).setLastError(null);
        require(updateById(recall), "召回关闭验证保存失败");
        saveOperation(command.getRequestId(), recall, null, "VERIFY", before, recall.getStatus(),
                command.getRemark() + "；结论=" + conclusion);
        return recall;
    }

    @Override
    public QmsBatchRecallDTO.Detail detail(Long id) {
        QmsBatchRecallEntity recall = requireRecall(id);
        return new QmsBatchRecallDTO.Detail().setRecall(recall)
                .setActions(actionMapper.selectList(new LambdaQueryWrapper<QmsBatchRecallActionEntity>()
                        .eq(QmsBatchRecallActionEntity::getRecallId, id)
                        .orderByAsc(QmsBatchRecallActionEntity::getCreateTime)
                        .orderByAsc(QmsBatchRecallActionEntity::getId)))
                .setOperations(operationMapper.selectList(new LambdaQueryWrapper<QmsBatchRecallOperationEntity>()
                        .eq(QmsBatchRecallOperationEntity::getRecallId, id)
                        .orderByAsc(QmsBatchRecallOperationEntity::getOperationTime)
                        .orderByAsc(QmsBatchRecallOperationEntity::getId)));
    }

    private EvidenceBundle evidence(String factory, String materialCode, String batchNo) {
        MesBatchTraceDTO.Query mesQuery = new MesBatchTraceDTO.Query().setFactoryCode(factory)
                .setMaterialCode(materialCode).setBatchNo(batchNo).setDetailLimit(50);
        WmsBatchStockTraceDTO.Query wmsQuery = new WmsBatchStockTraceDTO.Query().setFactoryCode(factory)
                .setMaterialCode(materialCode).setBatchNo(batchNo).setDetailLimit(100);
        QmsBatchQualityTraceDTO.Query qmsQuery = new QmsBatchQualityTraceDTO.Query().setFactoryCode(factory)
                .setMaterialCode(materialCode).setBatchNo(batchNo).setDetailLimit(100);
        R<MesBatchTraceDTO.Evidence> mesResponse = mesTaskService.batchTrace(mesQuery);
        R<WmsBatchStockTraceDTO.Evidence> wmsResponse = wmsAgentQueryService.batchStockTrace(wmsQuery);
        require(mesResponse != null && mesResponse.isOk() && mesResponse.getData() != null,
                "MES批次影响范围不可用，禁止生成召回候选");
        require(wmsResponse != null && wmsResponse.isOk() && wmsResponse.getData() != null,
                "WMS批次库存证据不可用，禁止生成召回候选");
        QmsBatchQualityTraceDTO.Evidence qms = qualityTraceService.query(qmsQuery);
        MesBatchTraceDTO.Evidence mes = mesResponse.getData();
        WmsBatchStockTraceDTO.Evidence wms = wmsResponse.getData();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("mes", mes);
        snapshot.put("wms", wms);
        snapshot.put("qms", qms);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("factoryCode", factory);
        facts.put("materialCode", materialCode);
        facts.put("batchNo", batchNo);
        facts.put("mesMatchedReportCount", mes.getMatchedReportCount());
        facts.put("mesAffectedReports", mes.getAffectedReports());
        facts.put("wmsPositiveStockRowCount", wms.getPositiveStockRowCount());
        facts.put("wmsUnitQuantities", wms.getUnitQuantities());
        facts.put("wmsStockPositions", wms.getStockPositions());
        facts.put("qmsInspectionTaskCount", qms.getInspectionTaskCount());
        facts.put("qmsOpenNcrCount", qms.getOpenNcrCount());
        facts.put("qmsRecords", qms.getRecords());
        Map<String, Object> downstream = new LinkedHashMap<>();
        downstream.put("factoryCode", factory);
        downstream.put("materialCode", materialCode);
        downstream.put("batchNo", batchNo);
        downstream.put("mesMatchedReportCount", mes.getMatchedReportCount());
        downstream.put("mesAffectedReports", mes.getAffectedReports());
        downstream.put("qmsInspectionTaskCount", qms.getInspectionTaskCount());
        downstream.put("qmsOpenNcrCount", qms.getOpenNcrCount());
        downstream.put("qmsRecords", qms.getRecords());
        return new EvidenceBundle(mes, wms, qms, json(snapshot), hash(json(facts)), hash(json(downstream)));
    }

    private void assertCandidateActionable(EvidenceBundle evidence) {
        require(!Boolean.TRUE.equals(evidence.mes().getTruncated()),
                "MES实际消耗影响超过50条，当前接口不能完整冻结下游范围，禁止生成可执行召回候选");
        assertUnitsActionable(evidence.wms());
        boolean evidenceExists = number(evidence.mes().getMatchedReportCount()) > 0
                || number(evidence.wms().getPositiveStockRowCount()) > 0
                || number(evidence.qms().getInspectionTaskCount()) > 0
                || number(evidence.qms().getNcrDetailCount()) > 0;
        require(evidenceExists, "MES、WMS、QMS均未发现该物料批次证据，禁止创建空召回候选");
    }

    private void assertUnitsActionable(WmsBatchStockTraceDTO.Evidence wms) {
        require(wms.getUnitQuantities().stream().noneMatch(unit -> StrUtil.isBlank(unit.getUnit())
                        || "UNKNOWN".equals(unit.getUnit())),
                "WMS存在缺失单位的该批次库存，必须先治理单位后再执行召回");
    }

    private void applyEvidence(QmsBatchRecallEntity recall, EvidenceBundle evidence) {
        recall.setEvidenceSnapshot(evidence.snapshot()).setEvidenceHash(evidence.evidenceHash())
                .setDownstreamScopeHash(evidence.downstreamScopeHash()).setObservedAt(LocalDateTime.now())
                .setMesMatchedReportCount(evidence.mes().getMatchedReportCount())
                .setWmsPositiveStockRowCount(evidence.wms().getPositiveStockRowCount())
                .setQmsOpenNcrCount(evidence.qms().getOpenNcrCount())
                .setCustomerNotificationRequired(number(evidence.mes().getMatchedReportCount()) > 0);
    }

    private void createCurrentHoldActions(QmsBatchRecallEntity recall, EvidenceBundle evidence) {
        for (WmsBatchStockTraceDTO.UnitQuantity unit : evidence.wms().getUnitQuantities()) {
            BigDecimal quantity = quantity(unit.getRecallFreezableQuantity());
            if (quantity.signum() <= 0) continue;
            String requestId = "QMS-RECALL-HOLD:" + recall.getId() + ":" + unit.getUnit()
                    + ":" + evidence.evidenceHash().substring(0, 16);
            QmsBatchRecallActionEntity existing = actionMapper.selectOne(
                    new LambdaQueryWrapper<QmsBatchRecallActionEntity>()
                            .eq(QmsBatchRecallActionEntity::getRequestId, requestId), false);
            if (existing != null) continue;
            QmsBatchRecallActionEntity action = new QmsBatchRecallActionEntity()
                    .setRecallId(recall.getId()).setActionNo("RA-" + IdUtil.getSnowflakeNextIdStr())
                    .setRequestId(requestId).setActionType("HOLD_STOCK").setTargetType("WMS_BATCH_UNIT")
                    .setTargetReference(recall.getMaterialCode() + "/" + recall.getBatchNo() + "/" + unit.getUnit())
                    .setMaterialUnit(unit.getUnit()).setRequestedQuantity(quantity).setAffectedQuantity(BigDecimal.ZERO)
                    .setStatus("PENDING").setAttemptCount(0).setEvidenceHash(evidence.evidenceHash())
                    .setFactoryCode(recall.getFactoryCode());
            require(actionMapper.insert(action) == 1, "召回库存隔离动作保存失败");
        }
    }

    private void executeHold(QmsBatchRecallEntity recall, QmsBatchRecallActionEntity action) {
        Actor executor = actor();
        action.setAttemptCount(Objects.requireNonNullElse(action.getAttemptCount(), 0) + 1)
                .setExecutedBy(executor.code()).setExecutedByName(executor.name()).setExecutedTime(LocalDateTime.now());
        try {
            WmsQualityStatusCommand command = new WmsQualityStatusCommand()
                    .setRequestId(action.getRequestId()).setHoldNo(recall.getRecallNo())
                    .setSourceType("QMS_BATCH_RECALL").setSourceId(String.valueOf(recall.getId()))
                    .setAction("HOLD").setMatCode(recall.getMaterialCode()).setMatBatch(recall.getBatchNo())
                    .setMatUnit(action.getMaterialUnit()).setQuantity(action.getRequestedQuantity())
                    .setReason(trim("批次召回 " + recall.getRecallNo() + "：" + recall.getReason(), 1000))
                    .setFactoryCode(recall.getFactoryCode());
            R<WmsQualityStatusResult> response = liveStockService.applyQualityStatus(command);
            WmsQualityStatusResult result = response == null ? null : response.getData();
            require(response != null && response.isOk() && result != null, "WMS未返回有效隔离回执");
            require(result.getRequestedQuantity() != null
                            && result.getRequestedQuantity().compareTo(action.getRequestedQuantity()) == 0,
                    "WMS隔离回执请求数量不一致");
            action.setRemoteOperationId(result.getOperationId()).setRemoteReference(result.getHoldNo())
                    .setAffectedQuantity(result.getAffectedQuantity()).setResultMessage(result.getMessage());
            if ("SUCCESS".equals(result.getStatus())) {
                require(result.getAffectedQuantity() != null
                                && result.getAffectedQuantity().compareTo(action.getRequestedQuantity()) == 0,
                        "WMS隔离成功回执影响数量不一致");
                action.setStatus("SUCCEEDED");
            } else {
                action.setStatus("PENDING");
            }
        } catch (Exception exception) {
            action.setStatus("UNKNOWN").setResultMessage(trim(rootMessage(exception), 1000));
        }
        require(actionMapper.updateById(action) == 1, "召回库存隔离回执保存失败");
    }

    private void createManualCustomerScopeAction(QmsBatchRecallEntity recall) {
        boolean exists = actionMapper.selectCount(new LambdaQueryWrapper<QmsBatchRecallActionEntity>()
                .eq(QmsBatchRecallActionEntity::getRequestId, "QMS-RECALL-CUSTOMER-SCOPE:" + recall.getId())) > 0;
        if (exists) return;
        QmsBatchRecallActionEntity action = new QmsBatchRecallActionEntity()
                .setRecallId(recall.getId()).setActionNo("RA-" + IdUtil.getSnowflakeNextIdStr())
                .setRequestId("QMS-RECALL-CUSTOMER-SCOPE:" + recall.getId())
                .setActionType("CUSTOMER_SCOPE_REVIEW").setTargetType("CUSTOMER_SCOPE_UNAVAILABLE")
                .setTargetReference("MES已命中" + recall.getMesMatchedReportCount() + "条实际消耗报工")
                .setStatus("MANUAL_REQUIRED").setAttemptCount(0)
                .setResultMessage("当前没有权威客户影响映射和外部通知接口；须登记正式通知或范围核对凭证")
                .setFactoryCode(recall.getFactoryCode());
        require(actionMapper.insert(action) == 1, "召回客户范围人工任务保存失败");
    }

    private boolean allHoldActionsSucceeded(Long recallId) {
        List<QmsBatchRecallActionEntity> holds = actions(recallId, "HOLD_STOCK");
        return holds.stream().allMatch(item -> "SUCCEEDED".equals(item.getStatus()));
    }

    private boolean customerScopeRecorded(Long recallId) {
        return actionMapper.selectCount(new LambdaQueryWrapper<QmsBatchRecallActionEntity>()
                .eq(QmsBatchRecallActionEntity::getRecallId, recallId)
                .eq(QmsBatchRecallActionEntity::getActionType, "CUSTOMER_NOTIFICATION")
                .eq(QmsBatchRecallActionEntity::getStatus, "SUCCEEDED")) > 0;
    }

    private List<QmsBatchRecallActionEntity> actions(Long recallId, String type) {
        return actionMapper.selectList(new LambdaQueryWrapper<QmsBatchRecallActionEntity>()
                .eq(QmsBatchRecallActionEntity::getRecallId, recallId)
                .eq(QmsBatchRecallActionEntity::getActionType, type)
                .orderByAsc(QmsBatchRecallActionEntity::getId));
    }

    private QmsBatchRecallEntity replay(String requestId, Long recallId, String operationType) {
        QmsBatchRecallOperationEntity operation = operationMapper.selectOne(
                new LambdaQueryWrapper<QmsBatchRecallOperationEntity>()
                        .eq(QmsBatchRecallOperationEntity::getRequestId, requestId), false);
        if (operation == null) return null;
        require(Objects.equals(operation.getRecallId(), recallId)
                        && Objects.equals(operation.getOperationType(), operationType),
                "幂等请求号已被其他召回操作占用");
        return requireRecall(recallId);
    }

    private void saveOperation(String requestId, QmsBatchRecallEntity recall,
                               QmsBatchRecallActionEntity action, String operationType,
                               String before, String after, String content) {
        Actor actor = actor();
        QmsBatchRecallOperationEntity operation = new QmsBatchRecallOperationEntity()
                .setRequestId(requestId).setRecallId(recall.getId())
                .setActionId(action == null ? null : action.getId()).setOperationType(operationType)
                .setBeforeStatus(before).setAfterStatus(after).setContent(trim(content, 4000))
                .setOperatorCode(actor.code()).setOperatorName(actor.name()).setOperationTime(LocalDateTime.now())
                .setFactoryCode(recall.getFactoryCode());
        require(operationMapper.insert(operation) == 1, "召回操作履历保存失败");
    }

    private QmsBatchRecallEntity lock(Long id) {
        QmsBatchRecallEntity recall = getOne(new LambdaQueryWrapper<QmsBatchRecallEntity>()
                .eq(QmsBatchRecallEntity::getId, id).last("FOR UPDATE"), false);
        require(recall != null, "召回治理单不存在");
        factoryAccess.requireRead(recall.getFactoryCode());
        return recall;
    }

    private QmsBatchRecallEntity requireRecall(Long id) {
        require(id != null, "召回治理单不能为空");
        QmsBatchRecallEntity recall = getById(id);
        require(recall != null, "召回治理单不存在");
        factoryAccess.requireRead(recall.getFactoryCode());
        return recall;
    }

    private void normalize(QmsBatchRecallDTO.ProposeCommand command) {
        normalizeRequest(command.getRequestId());
        command.setRequestId(command.getRequestId().trim());
        command.setMaterialCode(StrUtil.trim(command.getMaterialCode()));
        command.setBatchNo(StrUtil.trim(command.getBatchNo()));
        command.setSeverity(StrUtil.blankToDefault(command.getSeverity(), "").trim().toUpperCase());
        command.setReason(trim(command.getReason(), 2000));
        require(StrUtil.isNotBlank(command.getMaterialCode()) && StrUtil.isNotBlank(command.getBatchNo()),
                "召回物料和批次不能为空");
        require(SEVERITIES.contains(command.getSeverity()), "召回严重度必须为LOW、MEDIUM、HIGH或CRITICAL");
        require(StrUtil.isNotBlank(command.getReason()), "召回原因不能为空");
    }

    private void normalizeRequest(String requestId) {
        require(StrUtil.isNotBlank(requestId) && requestId.trim().length() <= 180, "幂等请求号不能为空且不能超过180字符");
    }

    private String currentFactory() {
        String factory = com.kitex.common.data.orgFilter.FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(factory), "请先在请求头factory-code中选择实际工厂");
        return factory;
    }

    private Actor actor() {
        MomUser user = SecurityUtils.getUser();
        require(user != null && StrUtil.isNotBlank(user.getUsername()), "召回治理必须实名登录");
        return new Actor(user.getUsername(), StrUtil.blankToDefault(user.getName(), user.getUsername()));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new CheckedException("召回证据序列化失败");
        }
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Objects.toString(value, "").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }

    private BigDecimal quantity(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private long number(Number value) {
        return value == null ? 0L : value.longValue();
    }

    private String trim(String value, int maxLength) {
        return StrUtil.maxLength(StrUtil.trim(value), maxLength);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return StrUtil.blankToDefault(current.getMessage(), current.getClass().getSimpleName());
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    private record Actor(String code, String name) {
    }

    private record EvidenceBundle(MesBatchTraceDTO.Evidence mes, WmsBatchStockTraceDTO.Evidence wms,
                                  QmsBatchQualityTraceDTO.Evidence qms, String snapshot,
                                  String evidenceHash, String downstreamScopeHash) {
    }
}
