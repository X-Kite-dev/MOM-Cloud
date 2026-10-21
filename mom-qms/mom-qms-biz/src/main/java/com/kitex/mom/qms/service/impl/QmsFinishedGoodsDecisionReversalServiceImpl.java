package com.kitex.mom.qms.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.qms.dto.QmsFinishedGoodsDecisionReversalDTO;
import com.kitex.mom.qms.dto.QmsFinishedGoodsDecisionReversalExecutionEvent;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.enums.InspectionTaskStatusEnum;
import com.kitex.mom.qms.mapper.*;
import com.kitex.mom.qms.service.QmsFinishedGoodsDecisionReversalService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 成品质量判定逆向授权实现。
 *
 * <p>原质检结果属于历史事实，不能为了红冲库存而改回待检。本服务冻结并复验精确任务事实，
 * 由不同岗位批准后签发一次性授权；WMS只能以与授权绑定的永久请求号幂等消费。</p>
 */
@Service
@RequiredArgsConstructor
public class QmsFinishedGoodsDecisionReversalServiceImpl
        extends ServiceImpl<QmsFinishedGoodsDecisionReversalMapper, QmsFinishedGoodsDecisionReversalEntity>
        implements QmsFinishedGoodsDecisionReversalService {

    private static final Set<String> CLOSED = Set.of("REJECTED", "WITHDRAWN", "STALE", "CONSUMED", "EXECUTED");
    private static final Set<String> RELEASE_RESULTS = Set.of(
            "QUALIFIED", "EXEMPTION", "RELEASE", "RECEIVE");
    private static final int MAX_TASKS = 1000;

    private final QmsFinishedGoodsDecisionReversalOperationMapper operationMapper;
    private final QmsInspectionProcedureTaskMapper procedureTaskMapper;
    private final QmsBadInfoMapper badInfoMapper;
    private final QmsBadInfoDetailMapper badInfoDetailMapper;
    private final QmsMrbProposalMapper mrbProposalMapper;
    private final QmsReworkOrderMapper reworkOrderMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QmsFinishedGoodsDecisionReversalEntity apply(
            QmsFinishedGoodsDecisionReversalDTO.ApplyCommand command) {
        require(command != null, "质量判定逆向申请不能为空");
        String requestId = required(command.getRequestId(), "申请请求号", 180);
        String reason = required(command.getReason(), "逆向原因", 2000);
        require(command.getWmsNoticeId() != null, "必须指定WMS成品入库通知");
        List<Long> taskIds = canonicalTaskIds(command.getSourceQualityTaskIds());
        String sourceFingerprint = fingerprint(command.getSourceQualityEvidenceFingerprint());
        String factory = requireFactory();

        QmsFinishedGoodsDecisionReversalEntity replay = getOne(
                new LambdaQueryWrapper<QmsFinishedGoodsDecisionReversalEntity>()
                        .eq(QmsFinishedGoodsDecisionReversalEntity::getRequestId, requestId), false);
        if (replay != null) {
            requireFactory(replay.getFactoryCode());
            require(Objects.equals(replay.getWmsNoticeId(), command.getWmsNoticeId())
                            && Objects.equals(replay.getSourceQualityTaskIds(), json(taskIds))
                            && Objects.equals(replay.getSourceQualityEvidenceFingerprint(), sourceFingerprint)
                            && Objects.equals(replay.getReason(), reason),
                    "质量判定逆向申请请求号已被不同事实使用");
            return replay;
        }
        QmsFinishedGoodsDecisionReversalEntity active = getOne(
                new LambdaQueryWrapper<QmsFinishedGoodsDecisionReversalEntity>()
                        .eq(QmsFinishedGoodsDecisionReversalEntity::getWmsNoticeId, command.getWmsNoticeId())
                        .eq(QmsFinishedGoodsDecisionReversalEntity::getSourceQualityEvidenceFingerprint,
                                sourceFingerprint)
                        .notIn(QmsFinishedGoodsDecisionReversalEntity::getStatus, CLOSED)
                        .last("FOR UPDATE"), false);
        if (active != null && "APPROVED".equals(active.getStatus())
                && active.getAuthorizationExpiresAt() != null
                && !active.getAuthorizationExpiresAt().isAfter(LocalDateTime.now())) {
            LocalDateTime expiredTime = LocalDateTime.now();
            active.setStatus("STALE").setAuthorizationExpiredTime(expiredTime)
                    .setAuthorizationCode(null).setAuthorizationExpiresAt(null)
                    .setApprovedQmsEvidenceFingerprint(null);
            require(updateById(active), "过期质量逆向授权关闭失败");
            operation(active, "EXPIRE:" + active.getId(), hash("EXPIRE|" + active.getId()),
                    "EXPIRE", "APPROVED", "STALE", "质量逆向授权已过期，须重新申请",
                    new Actor("SYSTEM", "系统"), expiredTime);
            active = null;
        }
        require(active == null, "该成品质量来源已有待处理逆向申请");

        TaskEvidence evidence = taskEvidence(taskIds, factory);
        Actor applicant = actor();
        LocalDateTime now = LocalDateTime.now();
        QmsFinishedGoodsDecisionReversalEntity row = new QmsFinishedGoodsDecisionReversalEntity()
                .setReversalNo("QFR-" + IdUtil.getSnowflakeNextIdStr())
                .setRequestId(requestId).setWmsNoticeId(command.getWmsNoticeId())
                .setSourceQualityTaskIds(json(taskIds))
                .setSourceQualityEvidenceFingerprint(sourceFingerprint)
                .setQmsEvidenceSnapshot(evidence.snapshot()).setQmsEvidenceFingerprint(evidence.fingerprint())
                .setStatus("SUBMITTED").setReason(reason).setApplicantCode(applicant.code())
                .setApplicantName(applicant.name()).setAppliedTime(now).setFactoryCode(factory);
        require(save(row), "质量判定逆向申请保存失败");
        operation(row, requestId, hash(json(List.of(command.getWmsNoticeId(), taskIds,
                        sourceFingerprint, reason))), "SUBMIT", null, "SUBMITTED",
                "冻结QMS任务=" + taskIds.size() + "，证据=" + evidence.fingerprint(), applicant, now);
        return row;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QmsFinishedGoodsDecisionReversalEntity review(
            QmsFinishedGoodsDecisionReversalDTO.ReviewCommand command) {
        require(command != null && command.getReversalId() != null, "质量逆向复核命令不能为空");
        String requestId = required(command.getRequestId(), "复核请求号", 180);
        String remark = required(command.getRemark(), "复核意见", 2000);
        String commandFingerprint = hash(json(List.of(command.getReversalId(),
                command.getApproved(), command.getExpectedEvidenceFingerprint(), remark)));
        QmsFinishedGoodsDecisionReversalOperationEntity replay = replay(requestId,
                command.getReversalId(), "REVIEW", commandFingerprint);
        if (replay != null) return requireReversal(command.getReversalId(), false);
        QmsFinishedGoodsDecisionReversalEntity row = requireReversal(command.getReversalId(), true);
        require("SUBMITTED".equals(row.getStatus()), "只有待复核质量逆向申请可以审核");
        require(Objects.equals(row.getQmsEvidenceFingerprint(),
                        fingerprint(command.getExpectedEvidenceFingerprint())),
                "页面QMS证据摘要已过期，请刷新后复核");
        Actor reviewer = actor();
        require(!Objects.equals(reviewer.code(), row.getApplicantCode()), "申请人与复核人必须分离");
        LocalDateTime now = LocalDateTime.now();
        String before = row.getStatus();
        if (!Boolean.TRUE.equals(command.getApproved())) {
            row.setStatus("REJECTED").setReviewerCode(reviewer.code()).setReviewerName(reviewer.name())
                    .setReviewRemark(remark).setReviewedTime(now);
            require(updateById(row), "质量逆向驳回结果保存失败");
            operation(row, requestId, commandFingerprint, "REVIEW", before, row.getStatus(), remark, reviewer, now);
            return row;
        }

        TaskEvidence fresh = taskEvidence(parseTaskIds(row.getSourceQualityTaskIds()), row.getFactoryCode());
        if (!Objects.equals(row.getQmsEvidenceFingerprint(), fresh.fingerprint())) {
            row.setStatus("STALE").setReviewerCode(reviewer.code()).setReviewerName(reviewer.name())
                    .setReviewRemark("QMS任务事实已变化，必须重新申请").setReviewedTime(now);
            require(updateById(row), "质量逆向证据失效状态保存失败");
            operation(row, requestId, commandFingerprint, "REVIEW", before, "STALE", row.getReviewRemark(), reviewer, now);
            return row;
        }
        row.setStatus("APPROVED").setReviewerCode(reviewer.code()).setReviewerName(reviewer.name())
                .setReviewRemark(remark).setReviewedTime(now)
                .setApprovedQmsEvidenceFingerprint(fresh.fingerprint())
                .setAuthorizationCode(IdUtil.fastSimpleUUID())
                .setAuthorizationExpiresAt(now.plusHours(72)).setAuthorizationExpiredTime(null);
        require(updateById(row), "质量逆向批准结果保存失败");
        operation(row, requestId, commandFingerprint, "REVIEW", before, "APPROVED",
                remark + "；授权有效期至=" + row.getAuthorizationExpiresAt(), reviewer, now);
        return row;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QmsFinishedGoodsDecisionReversalEntity withdraw(Long id,
                                                           QmsFinishedGoodsDecisionReversalDTO.WithdrawCommand command) {
        require(command != null, "撤回命令不能为空");
        String requestId = required(command.getRequestId(), "撤回请求号", 180);
        String reason = required(command.getReason(), "撤回原因", 2000);
        String commandFingerprint = hash(json(List.of(id, reason)));
        QmsFinishedGoodsDecisionReversalOperationEntity replay = replay(
                requestId, id, "WITHDRAW", commandFingerprint);
        if (replay != null) return requireReversal(id, false);
        QmsFinishedGoodsDecisionReversalEntity row = requireReversal(id, true);
        require("SUBMITTED".equals(row.getStatus()), "只有待复核质量逆向申请可以撤回");
        Actor applicant = actor();
        require(Objects.equals(applicant.code(), row.getApplicantCode()), "只有原申请人可以撤回");
        LocalDateTime now = LocalDateTime.now();
        row.setStatus("WITHDRAWN").setReviewRemark(reason).setReviewedTime(now);
        require(updateById(row), "质量逆向申请撤回失败");
        operation(row, requestId, commandFingerprint, "WITHDRAW",
                "SUBMITTED", "WITHDRAWN", reason, applicant, now);
        return row;
    }

    @Override
    @Transactional(readOnly = true)
    public QmsFinishedGoodsDecisionReversalDTO.Detail detail(Long id) {
        QmsFinishedGoodsDecisionReversalEntity row = requireReversal(id, false);
        return new QmsFinishedGoodsDecisionReversalDTO.Detail().setReversal(row)
                .setOperations(operationMapper.selectList(
                        new LambdaQueryWrapper<QmsFinishedGoodsDecisionReversalOperationEntity>()
                                .eq(QmsFinishedGoodsDecisionReversalOperationEntity::getReversalId, id)
                                .orderByAsc(QmsFinishedGoodsDecisionReversalOperationEntity::getId)));
    }

    @Override
    @Transactional(readOnly = true)
    public QmsFinishedGoodsDecisionReversalDTO.Authorization authorization(
            QmsFinishedGoodsDecisionReversalDTO.AuthorizationQuery query) {
        Source source = source(query);
        QmsFinishedGoodsDecisionReversalEntity row = getOne(
                new LambdaQueryWrapper<QmsFinishedGoodsDecisionReversalEntity>()
                        .eq(QmsFinishedGoodsDecisionReversalEntity::getWmsNoticeId, source.noticeId())
                        .eq(QmsFinishedGoodsDecisionReversalEntity::getSourceQualityTaskIds, source.taskIdsJson())
                        .eq(QmsFinishedGoodsDecisionReversalEntity::getSourceQualityEvidenceFingerprint,
                                source.sourceFingerprint())
                        .eq(QmsFinishedGoodsDecisionReversalEntity::getFactoryCode, source.factoryCode())
                        .orderByDesc(QmsFinishedGoodsDecisionReversalEntity::getId).last("LIMIT 1"), false);
        return authorization(row, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QmsFinishedGoodsDecisionReversalDTO.Authorization consume(
            QmsFinishedGoodsDecisionReversalDTO.ConsumeCommand command) {
        require(command != null && command.getAuthorizationId() != null, "质量逆向授权消费命令不能为空");
        Source source = source(command);
        String correctionRequestId = required(command.getWmsCorrectionRequestId(), "WMS红冲请求号", 180);
        String authorizationCode = required(command.getAuthorizationCode(), "质量逆向授权码", 64);
        QmsFinishedGoodsDecisionReversalEntity row = requireReversal(command.getAuthorizationId(), true);
        require(Objects.equals(row.getFactoryCode(), source.factoryCode())
                        && Objects.equals(row.getWmsNoticeId(), source.noticeId())
                        && Objects.equals(row.getSourceQualityTaskIds(), source.taskIdsJson())
                        && Objects.equals(row.getSourceQualityEvidenceFingerprint(), source.sourceFingerprint())
                        && Objects.equals(row.getAuthorizationCode(), authorizationCode),
                "质量逆向授权与当前WMS来源事实不匹配");
        if ("CONSUMED".equals(row.getStatus())) {
            require(Objects.equals(row.getConsumedByRequestId(), correctionRequestId),
                    "质量逆向授权已经被其他WMS红冲请求消费");
            require(row.getAuthorizationExpiresAt() != null
                            && row.getAuthorizationExpiresAt().isAfter(LocalDateTime.now()),
                    "当前WMS红冲申请消费的质量逆向授权已经过期，须关闭原申请后重新申请");
            TaskEvidence replayEvidence = taskEvidence(
                    parseTaskIds(row.getSourceQualityTaskIds()), row.getFactoryCode());
            require(Objects.equals(row.getApprovedQmsEvidenceFingerprint(), replayEvidence.fingerprint()),
                    "当前WMS红冲申请消费授权后QMS任务事实已变化，禁止执行实物红冲");
            return authorization(row, correctionRequestId);
        }
        require("APPROVED".equals(row.getStatus()), "质量逆向申请尚未批准或已经关闭");
        require(row.getAuthorizationExpiresAt() != null
                        && row.getAuthorizationExpiresAt().isAfter(LocalDateTime.now()),
                "质量逆向授权已经过期，请重新申请并复核");
        TaskEvidence fresh = taskEvidence(parseTaskIds(row.getSourceQualityTaskIds()), row.getFactoryCode());
        require(Objects.equals(row.getApprovedQmsEvidenceFingerprint(), fresh.fingerprint()),
                "批准后的QMS任务事实已变化，禁止消费原授权");
        LocalDateTime now = LocalDateTime.now();
        row.setStatus("CONSUMED").setConsumedByRequestId(correctionRequestId).setConsumedTime(now);
        require(updateById(row), "质量逆向授权消费状态保存失败");
        operation(row, "CONSUME:" + correctionRequestId,
                hash(json(List.of(source.noticeId(), source.taskIdsJson(), source.sourceFingerprint(),
                        authorizationCode, correctionRequestId))),
                "CONSUME", "APPROVED", "CONSUMED",
                "WMS永久请求=" + correctionRequestId, new Actor("WMS", "WMS成品红冲"), now);
        return authorization(row, correctionRequestId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QmsFinishedGoodsDecisionReversalExecutionEvent.Receipt receiveExecution(
            QmsFinishedGoodsDecisionReversalExecutionEvent event) {
        require(event != null && StrUtil.isNotBlank(event.getEventId()), "WMS质量逆向执行事件不能为空");
        require(Objects.equals(event.getFingerprint(), event.calculateFingerprint()),
                "WMS质量逆向执行事件指纹不匹配");
        require(event.getReverseQuantity() != null && event.getReverseQuantity().signum() > 0,
                "WMS质量逆向执行数量必须大于0");
        requireFactory(event.getFactoryCode());

        QmsFinishedGoodsDecisionReversalEntity replay = getOne(
                new LambdaQueryWrapper<QmsFinishedGoodsDecisionReversalEntity>()
                        .eq(QmsFinishedGoodsDecisionReversalEntity::getWmsExecutionEventId, event.getEventId())
                        .last("FOR UPDATE"), false);
        if (replay != null) {
            requireFactory(replay.getFactoryCode());
            requireExecutionMatches(replay, event);
            return executionReceipt(replay);
        }

        QmsFinishedGoodsDecisionReversalEntity row = requireReversal(event.getReversalId(), true);
        require("CONSUMED".equals(row.getStatus()) || "EXECUTED".equals(row.getStatus()),
                "质量逆向授权尚未被WMS消费或已经异常关闭");
        require(Objects.equals(row.getFactoryCode(), event.getFactoryCode())
                        && Objects.equals(row.getWmsNoticeId(), event.getWmsNoticeId())
                        && Objects.equals(row.getConsumedByRequestId(), event.getWmsCorrectionRequestId())
                        && Objects.equals(row.getSourceQualityEvidenceFingerprint(),
                        event.getSourceQualityEvidenceFingerprint())
                        && Objects.equals(row.getApprovedQmsEvidenceFingerprint(), event.getQmsEvidenceFingerprint()),
                "WMS实物执行结果与已消费质量逆向授权不一致");
        if ("EXECUTED".equals(row.getStatus())) {
            requireExecutionMatches(row, event);
            return executionReceipt(row);
        }

        LocalDateTime receivedTime = LocalDateTime.now();
        row.setStatus("EXECUTED").setWmsExecutionEventId(event.getEventId())
                .setWmsExecutionFingerprint(event.getFingerprint())
                .setWmsCorrectionId(event.getWmsCorrectionId())
                .setWmsReverseQuantity(event.getReverseQuantity())
                .setWmsExecutedTime(event.getExecutedTime())
                .setWmsExecutionReceivedTime(receivedTime);
        require(updateById(row), "WMS质量逆向执行永久结果保存失败");
        operation(row, event.getEventId(), event.getFingerprint(), "WMS_EXECUTED",
                "CONSUMED", "EXECUTED",
                "WMS红冲申请=" + event.getWmsCorrectionId() + "，红冲数量=" + event.getReverseQuantity(),
                new Actor("WMS", "WMS成品红冲"), receivedTime);
        return executionReceipt(row);
    }

    @Override
    @Transactional(readOnly = true)
    public QmsFinishedGoodsDecisionReversalExecutionEvent.Receipt executionReceipt(String eventId) {
        String stableEventId = required(eventId, "WMS质量逆向执行事件号", 180);
        QmsFinishedGoodsDecisionReversalEntity row = getOne(
                new LambdaQueryWrapper<QmsFinishedGoodsDecisionReversalEntity>()
                        .eq(QmsFinishedGoodsDecisionReversalEntity::getWmsExecutionEventId, stableEventId), false);
        if (row == null) return null;
        requireFactory(row.getFactoryCode());
        return executionReceipt(row);
    }

    @Override
    @Transactional(readOnly = true)
    public List<QmsFinishedGoodsDecisionReversalEntity> expiredCandidates(
            LocalDateTime cutoff, int limit) {
        require(cutoff != null, "质量逆向授权到期扫描时间不能为空");
        return baseMapper.selectExpiredCandidates(cutoff, Math.min(200, Math.max(1, limit)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean expireApprovedAuthorization(Long reversalId, LocalDateTime cutoff) {
        require(reversalId != null && cutoff != null, "质量逆向授权到期关闭参数不能为空");
        QmsFinishedGoodsDecisionReversalEntity row = getOne(
                new LambdaQueryWrapper<QmsFinishedGoodsDecisionReversalEntity>()
                        .eq(QmsFinishedGoodsDecisionReversalEntity::getId, reversalId)
                        .eq(QmsFinishedGoodsDecisionReversalEntity::getFactoryCode, requireFactory())
                        .last("FOR UPDATE"), false);
        if (row == null || !"APPROVED".equals(row.getStatus())
                || row.getAuthorizationExpiresAt() == null
                || row.getAuthorizationExpiresAt().isAfter(cutoff)) {
            return false;
        }
        LocalDateTime expiredTime = LocalDateTime.now();
        LocalDateTime authorizedUntil = row.getAuthorizationExpiresAt();
        row.setStatus("STALE").setAuthorizationExpiredTime(expiredTime)
                .setAuthorizationCode(null).setAuthorizationExpiresAt(null)
                .setApprovedQmsEvidenceFingerprint(null);
        require(updateById(row), "到期质量逆向授权关闭失败");
        operation(row, "EXPIRE:" + row.getId(), hash("EXPIRE|" + row.getId()),
                "EXPIRE", "APPROVED", "STALE",
                "授权有效期已于" + authorizedUntil + "结束，系统已自动关闭；须重新申请并异岗复核",
                new Actor("SYSTEM", "系统"), expiredTime);
        return true;
    }

    private void requireExecutionMatches(QmsFinishedGoodsDecisionReversalEntity row,
                                         QmsFinishedGoodsDecisionReversalExecutionEvent event) {
        require("EXECUTED".equals(row.getStatus())
                        && Objects.equals(row.getId(), event.getReversalId())
                        && Objects.equals(row.getWmsExecutionEventId(), event.getEventId())
                        && Objects.equals(row.getWmsExecutionFingerprint(), event.getFingerprint())
                        && Objects.equals(row.getWmsCorrectionId(), event.getWmsCorrectionId())
                        && row.getWmsReverseQuantity() != null
                        && row.getWmsReverseQuantity().compareTo(event.getReverseQuantity()) == 0
                        && Objects.equals(row.getWmsExecutedTime(), event.getExecutedTime()),
                "同一WMS质量逆向执行事件号已经保存了不同事实");
    }

    private QmsFinishedGoodsDecisionReversalExecutionEvent.Receipt executionReceipt(
            QmsFinishedGoodsDecisionReversalEntity row) {
        return new QmsFinishedGoodsDecisionReversalExecutionEvent.Receipt()
                .setEventId(row.getWmsExecutionEventId())
                .setFingerprint(row.getWmsExecutionFingerprint())
                .setReversalId(row.getId()).setWmsCorrectionId(row.getWmsCorrectionId())
                .setStatus("ACCEPTED").setAcceptedTime(row.getWmsExecutionReceivedTime());
    }

    private TaskEvidence taskEvidence(List<Long> taskIds, String factory) {
        List<QmsInspectionProcedureTaskEntity> tasks = procedureTaskMapper.selectList(
                new LambdaQueryWrapper<QmsInspectionProcedureTaskEntity>()
                        .in(QmsInspectionProcedureTaskEntity::getId, taskIds)
                        .eq(QmsInspectionProcedureTaskEntity::getFactoryCode, factory)
                        .orderByAsc(QmsInspectionProcedureTaskEntity::getId));
        require(tasks.size() == taskIds.size(), "部分QMS质检任务不存在或不属于当前工厂");
        assertNoOpenDisposition(taskIds, factory);
        List<Map<String, Object>> facts = new ArrayList<>();
        for (QmsInspectionProcedureTaskEntity task : tasks) {
            require(InspectionTaskStatusEnum.FINISHED.name().equals(task.getStatus())
                            && RELEASE_RESULTS.contains(task.getResult()),
                    "QMS任务" + task.getId() + "尚未形成可用于成品入库的正式放行结论");
            Map<String, Object> fact = new LinkedHashMap<>();
            fact.put("id", task.getId());
            fact.put("sourceTaskCode", task.getSourceTaskCode());
            fact.put("sourceRequestId", task.getSourceRequestId());
            fact.put("matCode", task.getMatCode());
            fact.put("processCode", task.getProcessCode());
            fact.put("batchNo", task.getBatchNo());
            fact.put("status", task.getStatus());
            fact.put("result", task.getResult());
            fact.put("num", task.getNum());
            fact.put("usableNum", task.getUsableNum());
            fact.put("handleNum", task.getHandleNum());
            fact.put("handleResult", task.getHandleResult());
            fact.put("latestInspectionTime", task.getLatestInspectionTime());
            facts.add(fact);
        }
        String snapshot = json(Map.of("version", "QMS_FG_DECISION_REVERSAL_V1", "tasks", facts));
        require(snapshot.getBytes(StandardCharsets.UTF_8).length <= 1024 * 1024,
                "QMS质量证据超过1MB，禁止截断授权");
        return new TaskEvidence(snapshot, hash(snapshot));
    }

    /**
     * 质量异常、MRB或返工仍在处理中时，不能撤销下游对该判定的依赖。
     */
    private void assertNoOpenDisposition(List<Long> taskIds, String factory) {
        Long openHeaders = badInfoMapper.selectCount(new LambdaQueryWrapper<QmsBadInfoEntity>()
                .in(QmsBadInfoEntity::getResourceTaskId, taskIds)
                .eq(QmsBadInfoEntity::getFactoryCode, factory)
                .notIn(QmsBadInfoEntity::getStatus, "CLOSED", "LEGACY_CLOSED"));
        require(openHeaders == null || openHeaders == 0,
                "精确QMS任务仍有关联未关闭NCR，须先完成处置和验证结案");

        List<QmsBadInfoDetailEntity> openDetails = badInfoDetailMapper.selectList(
                new LambdaQueryWrapper<QmsBadInfoDetailEntity>()
                        .in(QmsBadInfoDetailEntity::getResourceTaskId, taskIds)
                        .eq(QmsBadInfoDetailEntity::getFactoryCode, factory)
                        .notIn(QmsBadInfoDetailEntity::getStatus, "CLOSED", "LEGACY_CLOSED")
                        .select(QmsBadInfoDetailEntity::getId));
        require(openDetails.isEmpty(), "精确QMS任务仍有未关闭NCR分类，须先完成MRB处置和独立验证");

        List<QmsBadInfoDetailEntity> allDetails = badInfoDetailMapper.selectList(
                new LambdaQueryWrapper<QmsBadInfoDetailEntity>()
                        .in(QmsBadInfoDetailEntity::getResourceTaskId, taskIds)
                        .eq(QmsBadInfoDetailEntity::getFactoryCode, factory)
                        .select(QmsBadInfoDetailEntity::getId));
        if (!allDetails.isEmpty()) {
            List<Long> detailIds = allDetails.stream().map(QmsBadInfoDetailEntity::getId).toList();
            Long openMrb = mrbProposalMapper.selectCount(new LambdaQueryWrapper<QmsMrbProposal>()
                    .in(QmsMrbProposal::getDetailId, detailIds)
                    .eq(QmsMrbProposal::getFactoryCode, factory)
                    .notIn(QmsMrbProposal::getStatus, "APPLIED", "REJECTED", "WITHDRAWN"));
            require(openMrb == null || openMrb == 0,
                    "精确QMS任务仍有关联未终结MRB会签，禁止签发逆向授权");
        }

        Long openRework = reworkOrderMapper.selectCount(new LambdaQueryWrapper<QmsReworkOrderEntity>()
                .eq(QmsReworkOrderEntity::getFactoryCode, factory)
                .and(wrapper -> wrapper.in(QmsReworkOrderEntity::getInspectionTaskId, taskIds)
                        .or().in(QmsReworkOrderEntity::getReinspectionTaskId, taskIds))
                .ne(QmsReworkOrderEntity::getStatus, "CLOSED"));
        require(openRework == null || openRework == 0,
                "精确QMS任务仍有关联返工或复检流程，须先闭环后再申请逆向");
    }

    private Source source(QmsFinishedGoodsDecisionReversalDTO.AuthorizationQuery query) {
        require(query != null && query.getWmsNoticeId() != null, "WMS质量来源查询不能为空");
        String factory = StrUtil.isBlank(query.getFactoryCode()) ? requireFactory() : query.getFactoryCode().trim();
        requireFactory(factory);
        return new Source(query.getWmsNoticeId(), json(canonicalTaskIds(query.getSourceQualityTaskIds())),
                fingerprint(query.getSourceQualityEvidenceFingerprint()), factory);
    }

    private QmsFinishedGoodsDecisionReversalDTO.Authorization authorization(
            QmsFinishedGoodsDecisionReversalEntity row, String correctionRequestId) {
        if (row == null) return new QmsFinishedGoodsDecisionReversalDTO.Authorization()
                .setStatus("NOT_APPLIED").setEligible(false).setMessage("尚未发起质量判定逆向申请");
        boolean approved = "APPROVED".equals(row.getStatus())
                && row.getAuthorizationExpiresAt() != null
                && row.getAuthorizationExpiresAt().isAfter(LocalDateTime.now());
        boolean replay = "CONSUMED".equals(row.getStatus())
                && correctionRequestId != null
                && Objects.equals(row.getConsumedByRequestId(), correctionRequestId);
        String message = approved ? "质量判定逆向已异岗批准，可由WMS一次性消费"
                : replay ? "质量判定逆向授权已由当前WMS请求消费"
                : "APPROVED".equals(row.getStatus()) ? "质量判定逆向授权已过期"
                : "CONSUMED".equals(row.getStatus()) ? "质量判定逆向授权已被消费"
                : "质量判定逆向当前状态：" + row.getStatus();
        return new QmsFinishedGoodsDecisionReversalDTO.Authorization()
                .setReversalId(row.getId()).setReversalNo(row.getReversalNo()).setStatus(row.getStatus())
                .setEligible(approved || replay).setMessage(message)
                .setAuthorizationCode(approved || replay ? row.getAuthorizationCode() : null)
                .setAuthorizationExpiresAt(row.getAuthorizationExpiresAt())
                .setQmsEvidenceFingerprint(row.getQmsEvidenceFingerprint())
                .setApplicantName(row.getApplicantName()).setReviewerName(row.getReviewerName());
    }

    private QmsFinishedGoodsDecisionReversalEntity requireReversal(Long id, boolean lock) {
        QmsFinishedGoodsDecisionReversalEntity row = getOne(
                new LambdaQueryWrapper<QmsFinishedGoodsDecisionReversalEntity>()
                        .eq(QmsFinishedGoodsDecisionReversalEntity::getId, id)
                        .last(lock ? "FOR UPDATE" : ""), false);
        require(row != null, "质量判定逆向申请不存在");
        requireFactory(row.getFactoryCode());
        return row;
    }

    private QmsFinishedGoodsDecisionReversalOperationEntity replay(
            String requestId, Long reversalId, String operationType, String commandFingerprint) {
        QmsFinishedGoodsDecisionReversalOperationEntity row = operationMapper.selectOne(
                new LambdaQueryWrapper<QmsFinishedGoodsDecisionReversalOperationEntity>()
                        .eq(QmsFinishedGoodsDecisionReversalOperationEntity::getRequestId, requestId), false);
        if (row != null) require(Objects.equals(row.getReversalId(), reversalId)
                        && Objects.equals(row.getOperationType(), operationType)
                        && Objects.equals(row.getCommandFingerprint(), commandFingerprint),
                "质量逆向操作请求号已被不同命令使用");
        return row;
    }

    private void operation(QmsFinishedGoodsDecisionReversalEntity row, String requestId,
                           String commandFingerprint, String type, String before, String after,
                           String content, Actor actor, LocalDateTime time) {
        require(operationMapper.insert(new QmsFinishedGoodsDecisionReversalOperationEntity()
                .setReversalId(row.getId()).setRequestId(requestId)
                .setCommandFingerprint(commandFingerprint).setOperationType(type)
                .setBeforeStatus(before).setAfterStatus(after).setContent(content)
                .setOperatorCode(actor.code()).setOperatorName(actor.name()).setOperationTime(time)
                .setFactoryCode(row.getFactoryCode())) == 1, "质量逆向操作履历保存失败");
    }

    private List<Long> canonicalTaskIds(List<Long> source) {
        require(source != null && !source.isEmpty() && source.size() <= MAX_TASKS,
                "QMS任务集合必须包含1至1000个任务");
        List<Long> result = source.stream().filter(Objects::nonNull).distinct().sorted().toList();
        require(result.size() == source.size(), "QMS任务集合不能包含空值或重复项");
        return result;
    }

    private List<Long> parseTaskIds(String value) {
        try {
            return canonicalTaskIds(objectMapper.readValue(value,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Long.class)));
        } catch (JsonProcessingException exception) {
            throw new CheckedException("冻结的QMS任务集合已经损坏");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new CheckedException("质量逆向证据序列化失败");
        }
    }

    private String fingerprint(String value) {
        String result = required(value, "质量证据指纹", 64).toLowerCase();
        require(result.matches("[0-9a-f]{64}"), "质量证据指纹必须是64位SHA-256");
        return result;
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }

    private String required(String value, String label, int maxLength) {
        String result = StrUtil.trim(value);
        require(StrUtil.isNotBlank(result) && result.length() <= maxLength, label + "不能为空且长度不能超过" + maxLength);
        return result;
    }

    private Actor actor() {
        MomUser user = SecurityUtils.getUser();
        require(user != null && StrUtil.isNotBlank(user.getUsername()), "质量判定逆向必须实名登录");
        return new Actor(user.getUsername(), StrUtil.blankToDefault(user.getName(), user.getUsername()));
    }

    private String requireFactory() {
        String factory = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(factory), "请先在请求头factory-code中选择实际工厂");
        return factory.trim();
    }

    private void requireFactory(String factory) {
        require(StrUtil.isNotBlank(factory) && Objects.equals(requireFactory(), factory),
                "质量逆向申请不属于当前工厂");
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    private record Actor(String code, String name) {
    }

    private record TaskEvidence(String snapshot, String fingerprint) {
    }

    private record Source(Long noticeId, String taskIdsJson, String sourceFingerprint, String factoryCode) {
    }
}
