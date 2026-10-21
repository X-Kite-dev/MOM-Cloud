package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.oms.dto.OmsOrderChangeReviewCommand;
import com.kitex.mom.oms.dto.OmsOrderSupplyReplanDTO;
import com.kitex.mom.oms.entity.*;
import com.kitex.mom.oms.enums.OrderStateEnum;
import com.kitex.mom.plan.dto.PlanMrpSupplyWithdrawalDTO;
import com.kitex.mom.plan.entity.PlanMatMrpEntity;
import com.kitex.mom.plan.entity.PlnProductDemandEntity;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.*;
import com.kitex.mom.pms.api.feign.RemotePmsPlanService;
import com.kitex.mom.pms.dto.PmsOmsSupplyWithdrawalDTO;
import com.kitex.mom.pms.dto.PmsOrderChangeDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * OMS订单变更对已下达但尚未执行供应的证据冻结、异岗审批、分阶段撤回和MRP重算编排。
 */
@Service
@RequiredArgsConstructor
public class OmsOrderSupplyReplanServiceImpl
        extends ServiceImpl<OmsOrderSupplyReplanMapper, OmsOrderSupplyReplanEntity>
        implements OmsOrderSupplyReplanService {

    private static final Set<String> ACTIVE = Set.of(
            "SUBMITTED", "APPROVED", "EXECUTING", "WAITING_PMS_REVIEW", "FAILED",
            "READY_FOR_FINAL_REVIEW", "READY_FOR_REISSUE");
    private static final Duration EXECUTION_TAKEOVER_AFTER = Duration.ofMinutes(10);

    private final OmsOrderSupplyReplanLineMapper lineMapper;
    private final OmsOrderSupplyReplanOperationMapper operationMapper;
    private final OmsOrderChangeVersionMapper versionMapper;
    private final OmsOrderBaseReviewMapper reviewMapper;
    private final OmsOrderDetailsService detailsService;
    private final OmsOrderDetailsReviewService detailsReviewService;
    private final PlnProductDemandService demandService;
    private final PlanMatMrpService mrpService;
    private final RemotePmsPlanService remotePmsPlanService;
    private final OmsOrderChangeLedgerService changeLedger;
    private final OmsOrderBaseReviewService orderReviewService;
    private final PlatformTransactionManager transactionManager;

    @Override
    public Page<OmsOrderSupplyReplanEntity> page(SearchPage page, OmsOrderSupplyReplanDTO.Query query) {
        Context context = context();
        OmsOrderSupplyReplanDTO.Query condition = query == null ? new OmsOrderSupplyReplanDTO.Query() : query;
        require(StrUtil.isBlank(condition.getFactoryCode())
                        || Objects.equals(condition.getFactoryCode(), context.factory()),
                "查询工厂与当前factory-code不一致");
        return baseMapper.selectPage(page.toMpPage(), new LambdaQueryWrapper<OmsOrderSupplyReplanEntity>()
                .eq(OmsOrderSupplyReplanEntity::getFactoryCode, context.factory())
                .like(StrUtil.isNotBlank(condition.getOrderCode()), OmsOrderSupplyReplanEntity::getOrderCode,
                        condition.getOrderCode())
                .eq(StrUtil.isNotBlank(condition.getStatus()), OmsOrderSupplyReplanEntity::getStatus,
                        condition.getStatus())
                .orderByDesc(OmsOrderSupplyReplanEntity::getSubmittedTime)
                .orderByDesc(OmsOrderSupplyReplanEntity::getId));
    }

    @Override
    public OmsOrderSupplyReplanDTO.Detail detail(Long id) {
        OmsOrderSupplyReplanEntity entity = requireReplan(id, false);
        requireFactory(entity.getFactoryCode());
        return detailOf(entity);
    }

    @Override
    public OmsOrderSupplyReplanDTO.Detail latestByReview(Long reviewId) {
        require(reviewId != null, "订单变更评审ID不能为空");
        Context context = context();
        OmsOrderSupplyReplanEntity entity = baseMapper.selectOne(
                new LambdaQueryWrapper<OmsOrderSupplyReplanEntity>()
                        .eq(OmsOrderSupplyReplanEntity::getReviewId, reviewId)
                        .eq(OmsOrderSupplyReplanEntity::getFactoryCode, context.factory())
                        .orderByDesc(OmsOrderSupplyReplanEntity::getSubmittedTime)
                        .orderByDesc(OmsOrderSupplyReplanEntity::getId).last("LIMIT 1"));
        return entity == null ? new OmsOrderSupplyReplanDTO.Detail() : detailOf(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OmsOrderSupplyReplanDTO.Detail submit(OmsOrderSupplyReplanDTO.SubmitCommand command) {
        require(command != null && command.getReviewId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getReason()), "级联重排申请必须提供订单评审、请求号和原因");
        Context context = context();
        String requestId = text(command.getRequestId(), 64, "提交请求号");
        String reason = text(command.getReason(), 500, "申请原因");
        String requestFingerprint = hash(command.getReviewId() + "|" + reason);
        baseMapper.lockKey("REQUEST:" + context.tenantId() + ':' + requestId);
        OmsOrderSupplyReplanEntity replay = baseMapper.selectOne(
                new LambdaQueryWrapper<OmsOrderSupplyReplanEntity>()
                        .eq(OmsOrderSupplyReplanEntity::getSubmitRequestId, requestId));
        if (replay != null) {
            require(Objects.equals(replay.getRequestFingerprint(), requestFingerprint),
                    "提交请求号已被不同级联重排申请使用");
            requireFactory(replay.getFactoryCode());
            return detailOf(replay);
        }
        OmsOrderBaseReviewEntity review = requireReview(command.getReviewId(), true);
        requireFactory(review.getFactoryCode());
        OmsOrderChangeVersionEntity version = requireVersion(review, true);
        require(Objects.equals(version.getApplicantCode(), context.user().getUsername()),
                "只有本轮订单变更申请人可以提交供应级联重排；复核人必须由另一账号担任");
        baseMapper.lockKey("VERSION:" + context.tenantId() + ':' + version.getId());
        require(baseMapper.selectCount(new LambdaQueryWrapper<OmsOrderSupplyReplanEntity>()
                        .eq(OmsOrderSupplyReplanEntity::getOrderChangeVersionId, version.getId())
                        .in(OmsOrderSupplyReplanEntity::getStatus, ACTIVE)) == 0,
                "本轮订单变更已有待处理或可续跑的供应级联重排");

        List<OmsOrderDetailsReviewEntity> candidates = candidateLines(review.getOrderCode());
        String current = changeLedger.currentSnapshot(review.getOrderCode(), review.getFactoryCode());
        String proposed = changeLedger.proposalFromReview(candidates);
        require(Objects.equals(version.getBeforeSnapshot(), current)
                        && Objects.equals(version.getProposedSnapshot(), proposed),
                "订单、PLAN/MRP或变更候选已变化，请重新提交订单变更");
        List<LineEvidence> evidence = captureAffected(version, review, candidates);
        require(!evidence.isEmpty(), "本轮订单变更没有已下达且可安全撤回的供应；请直接评审或先处理阻断事实");

        long replanId = IdUtil.getSnowflakeNextId();
        String evidenceFingerprint = evidenceFingerprint(version, evidence);
        OmsOrderSupplyReplanEntity entity = new OmsOrderSupplyReplanEntity()
                .setReplanNo("OSR-" + IdUtil.getSnowflakeNextIdStr())
                .setOrderChangeVersionId(version.getId()).setReviewId(review.getId())
                .setOrderCode(review.getOrderCode()).setSubmitRequestId(requestId)
                .setRequestFingerprint(requestFingerprint).setBeforeSnapshot(current)
                .setProposedSnapshot(proposed).setEvidenceFingerprint(evidenceFingerprint)
                .setReason(reason).setStatus("SUBMITTED")
                .setApplicantCode(context.user().getUsername()).setApplicantName(userName(context.user()))
                .setSubmittedTime(LocalDateTime.now()).setRetryCount(0)
                .setSupplyPhaseStatus("PENDING").setReservationPhaseStatus("PENDING")
                .setFinalReviewStatus("PENDING").setReissuePhaseStatus("PENDING")
                .setFactoryCode(context.factory());
        entity.setId(replanId);
        entity.setTenantId(context.tenantId());
        require(baseMapper.insert(entity) == 1, "供应级联重排申请保存失败");
        int sequence = 0;
        for (LineEvidence item : evidence) {
            PlanMrpSupplyWithdrawalDTO.Evidence supply = item.supply();
            OmsOrderSupplyReplanLineEntity line = new OmsOrderSupplyReplanLineEntity()
                    .setReplanId(replanId).setSequenceNo(++sequence).setOrderDetailId(item.orderDetailId())
                    .setDemandId(item.demandId()).setDemandNo(item.demandNo()).setMrpId(supply.getMrpId())
                    .setMaterialCode(supply.getMaterialCode()).setSupplyType(supply.getSupplyType())
                    .setSupplySourceId(supply.getSourceId()).setSupplySourceNo(supply.getSourceNo())
                    .setSupplyQuantity(supply.getSourceQuantity()).setEvidenceFingerprint(supply.getEvidenceFingerprint())
                    .setDownstreamEvidenceFingerprint(supply.getDownstreamEvidenceFingerprint())
                    .setEvidenceSnapshot(supply.getEvidenceSnapshot())
                    .setSupplyStatus("NOT_APPLICABLE".equals(supply.getStatus()) ? "NOT_APPLICABLE" : "PENDING")
                    .setPmsOrderId(item.pms() == null ? null : item.pms().orderId())
                    .setPmsOrderNo(item.pms() == null ? null : item.pms().orderNo())
                    .setPmsOrderDetailId(item.pms() == null ? null : item.pms().orderDetailId())
                    .setPmsEvidenceFingerprint(item.pms() == null ? null : item.pms().evidenceFingerprint())
                    .setPmsChangeStatus(item.pms() == null ? "NOT_REQUIRED" : "PENDING")
                    .setReservationStatus("PENDING").setFactoryCode(context.factory());
            line.setTenantId(context.tenantId());
            require(lineMapper.insert(line) == 1, "供应级联重排逐MRP证据保存失败");
        }
        audit(entity, requestId, requestFingerprint, "SUBMIT", null, "SUBMITTED", reason, context.user());
        return detailOf(entity);
    }

    @Override
    public OmsOrderSupplyReplanDTO.Detail review(OmsOrderSupplyReplanDTO.ReviewCommand command) {
        require(command != null && command.getReplanId() != null && StrUtil.isNotBlank(command.getRequestId())
                        && StrUtil.isNotBlank(command.getExpectedEvidenceFingerprint()) && command.getApproved() != null
                        && StrUtil.isNotBlank(command.getReason()),
                "级联重排复核必须提供业务单、请求号、冻结证据、结论和意见");
        String requestId = text(command.getRequestId(), 64, "复核请求号");
        String reason = text(command.getReason(), 500, "复核意见");
        String expected = text(command.getExpectedEvidenceFingerprint(), 64, "冻结证据指纹");
        String fingerprint = hash(command.getReplanId() + "|" + expected + "|" + command.getApproved() + "|" + reason);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        OmsOrderSupplyReplanEntity decision = tx.execute(status -> {
            baseMapper.lockKey("REVIEW:" + requestId);
            OmsOrderSupplyReplanOperationEntity old = operationMapper.selectOne(
                    new LambdaQueryWrapper<OmsOrderSupplyReplanOperationEntity>()
                            .eq(OmsOrderSupplyReplanOperationEntity::getRequestId, requestId));
            if (old != null) {
                require(Objects.equals(old.getReplanId(), command.getReplanId())
                                && Objects.equals(old.getRequestFingerprint(), fingerprint),
                        "复核请求号已被不同级联重排命令使用");
                return requireReplan(command.getReplanId(), false);
            }
            OmsOrderSupplyReplanEntity entity = requireReplan(command.getReplanId(), true);
            requireFactory(entity.getFactoryCode());
            require("SUBMITTED".equals(entity.getStatus()), "只有待复核级联重排允许审批");
            require(Objects.equals(entity.getEvidenceFingerprint(), expected), "冻结证据指纹不一致，请刷新后重试");
            Context context = context();
            require(!Objects.equals(entity.getApplicantCode(), context.user().getUsername()),
                    "供应级联重排申请人与复核人必须分离");
            OmsOrderChangeVersionEntity version = versionMapper.selectById(entity.getOrderChangeVersionId());
            require(version != null && "SUBMITTED".equals(version.getStatus()), "订单变更版本已终结，不能继续供应级联重排");
            require(!Objects.equals(version.getApplicantCode(), context.user().getUsername()),
                    "订单变更申请人与级联复核人必须分离");
            String before = entity.getStatus();
            if (!Boolean.TRUE.equals(command.getApproved())) {
                entity.setStatus("REJECTED").setApproved(false);
            } else if (!evidenceStillCurrent(entity)) {
                entity.setStatus("STALE").setApproved(false).setLastError("订单、候选或下游供应事实已变化，请重新申请");
            } else {
                entity.setStatus("APPROVED").setApproved(true)
                        .setExecutionRequestId("OSR-EXEC-" + entity.getId());
            }
            entity.setReviewRequestId(requestId).setReviewFingerprint(fingerprint).setReviewReason(reason)
                    .setReviewerCode(context.user().getUsername()).setReviewerName(userName(context.user()))
                    .setReviewedTime(LocalDateTime.now());
            require(baseMapper.updateById(entity) == 1, "供应级联重排复核状态保存失败");
            audit(entity, requestId, fingerprint,
                    Boolean.TRUE.equals(command.getApproved()) ? "REVIEW_APPROVE" : "REVIEW_REJECT",
                    before, entity.getStatus(), reason, context.user());
            return entity;
        });
        if (decision != null && "APPROVED".equals(decision.getStatus())) execute(decision.getId());
        return detail(command.getReplanId());
    }

    @Override
    public OmsOrderSupplyReplanDTO.Detail retry(OmsOrderSupplyReplanDTO.RetryCommand command) {
        require(command != null && command.getReplanId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getReason()), "级联重排续跑必须提供业务单、请求号和原因");
        String requestId = text(command.getRequestId(), 64, "续跑请求号");
        String reason = text(command.getReason(), 500, "续跑原因");
        String fingerprint = hash(command.getReplanId() + "|" + reason);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Boolean execute = tx.execute(status -> {
            baseMapper.lockKey("RETRY:" + requestId);
            OmsOrderSupplyReplanOperationEntity old = operationMapper.selectOne(
                    new LambdaQueryWrapper<OmsOrderSupplyReplanOperationEntity>()
                            .eq(OmsOrderSupplyReplanOperationEntity::getRequestId, requestId));
            if (old != null) {
                require(Objects.equals(old.getReplanId(), command.getReplanId())
                                && Objects.equals(old.getRequestFingerprint(), fingerprint),
                        "续跑请求号已被不同级联重排命令使用");
                return false;
            }
            OmsOrderSupplyReplanEntity entity = requireReplan(command.getReplanId(), true);
            requireFactory(entity.getFactoryCode());
            require(Set.of("WAITING_PMS_REVIEW", "FAILED", "READY_FOR_FINAL_REVIEW", "READY_FOR_REISSUE", "EXECUTING")
                            .contains(entity.getStatus()),
                    "只有等待采购复核、失败、待最终评审、待重下达或超时执行中的级联重排允许续跑");
            if ("EXECUTING".equals(entity.getStatus())) {
                LocalDateTime lastUpdate = entity.getUpdateTime();
                require(lastUpdate != null && lastUpdate.plus(EXECUTION_TAKEOVER_AFTER).isBefore(LocalDateTime.now()),
                        "级联重排仍在执行保护期内，请勿重复执行；超过10分钟后可人工接管续跑");
            }
            String before = entity.getStatus();
            entity.setStatus("APPROVED").setLastError(null)
                    .setRetryCount(Optional.ofNullable(entity.getRetryCount()).orElse(0) + 1);
            require(baseMapper.updateById(entity) == 1, "级联重排续跑状态保存失败");
            audit(entity, requestId, fingerprint, "RETRY", before, "APPROVED", reason, context().user());
            return true;
        });
        if (Boolean.TRUE.equals(execute)) execute(command.getReplanId());
        return detail(command.getReplanId());
    }

    private void execute(Long replanId) {
        if (!markExecuting(replanId)) return;
        try {
            PmsGateResult pmsGate = ensurePmsWithdrawals(replanId);
            if (pmsGate != PmsGateResult.READY) return;
            executeSupply(replanId);
            executeReservationRelease(replanId);
            executeFinalReview(replanId);
            executeReissue(replanId);
        } catch (Exception ex) {
            markFailed(replanId, ex);
        }
    }

    private boolean markExecuting(Long replanId) {
        return Boolean.TRUE.equals(new TransactionTemplate(transactionManager).execute(status -> {
            OmsOrderSupplyReplanEntity entity = requireReplan(replanId, true);
            if (Set.of("SUCCEEDED", "EXECUTING").contains(entity.getStatus())) return false;
            require("APPROVED".equals(entity.getStatus()), "级联重排尚未批准或状态不允许执行");
            entity.setStatus("EXECUTING").setLastError(null);
            require(baseMapper.updateById(entity) == 1, "级联重排执行状态保存失败");
            return true;
        }));
    }

    /**
     * 为已经形成采购订单的MRP建立并核对PMS行级撤回申请。
     * 同一采购订单的目标行合并为一张申请，避免多张活动申请互相阻塞。
     */
    private PmsGateResult ensurePmsWithdrawals(Long replanId) {
        OmsOrderSupplyReplanEntity entity = requireReplan(replanId, false);
        Map<Long, List<OmsOrderSupplyReplanLineEntity>> groups = lines(replanId).stream()
                .filter(line -> line.getPmsOrderId() != null)
                .collect(java.util.stream.Collectors.groupingBy(OmsOrderSupplyReplanLineEntity::getPmsOrderId,
                        LinkedHashMap::new, java.util.stream.Collectors.toList()));
        boolean waiting = false;
        for (Map.Entry<Long, List<OmsOrderSupplyReplanLineEntity>> entry : groups.entrySet()) {
            List<OmsOrderSupplyReplanLineEntity> group = entry.getValue();
            String evidence = group.get(0).getPmsEvidenceFingerprint();
            require(StrUtil.isNotBlank(evidence) && group.stream().allMatch(line ->
                            Objects.equals(evidence, line.getPmsEvidenceFingerprint())
                                    && line.getPmsOrderDetailId() != null),
                    "同一采购订单的OMS冻结行缺少一致的PMS证据");
            String requestId = limit("OSR-PMS-" + replanId + '-' + entry.getKey(), 128);
            PmsOmsSupplyWithdrawalDTO.SubmitCommand command = new PmsOmsSupplyWithdrawalDTO.SubmitCommand();
            command.setPmsOrderId(entry.getKey());
            command.setOrderDetailIds(group.stream().map(OmsOrderSupplyReplanLineEntity::getPmsOrderDetailId)
                    .distinct().sorted().toList());
            command.setRequestId(requestId);
            command.setExpectedEvidenceFingerprint(evidence);
            command.setReason(limit("OMS供应级联[" + entity.getReplanNo() + "]：" + entity.getReason(), 500));
            command.setSourceReplanId(String.valueOf(entity.getId()));
            command.setSourceReplanNo(entity.getReplanNo());
            command.setApplicantCode(entity.getApplicantCode());
            command.setApplicantName(entity.getApplicantName());
            R<PmsOmsSupplyWithdrawalDTO.Receipt> response = remotePmsPlanService.submitOmsLineWithdrawal(command);
            require(response != null && response.isOk() && response.getData() != null,
                    "PMS未返回采购订单行撤回永久回执：" + (response == null ? "无响应" : response.getMsg()));
            PmsOmsSupplyWithdrawalDTO.Receipt receipt = response.getData();
            require(Objects.equals(receipt.getPmsOrderId(), entry.getKey())
                            && Objects.equals(receipt.getRequestId(), requestId)
                            && Objects.equals(receipt.getEvidenceFingerprint(), evidence),
                    "PMS采购订单行撤回回执与OMS冻结事实不一致");
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> group.forEach(row -> {
                OmsOrderSupplyReplanLineEntity locked = requireLine(row.getId(), true);
                locked.setPmsChangeRequestId(receipt.getChangeRequestId())
                        .setPmsChangeRequestNo(receipt.getChangeRequestNo())
                        .setPmsChangeSubmitRequestId(requestId).setPmsChangeStatus(receipt.getStatus())
                        .setPmsChangeMessage(limit(receipt.getMessage(), 500));
                if (!"SUBMITTED".equals(receipt.getStatus())) locked.setPmsChangeHandledTime(LocalDateTime.now());
                if ("SUBMITTED".equals(receipt.getStatus())) locked.setSupplyStatus("WAITING_PMS_REVIEW");
                require(lineMapper.updateById(locked) == 1, "PMS采购撤回回执写入OMS级联行失败");
            }));
            if (Set.of("REJECTED", "STALE").contains(receipt.getStatus())) {
                markPmsTerminal(entity, receipt);
                return PmsGateResult.TERMINAL;
            }
            require(Set.of("SUBMITTED", "SUCCEEDED").contains(receipt.getStatus()),
                    "PMS采购撤回返回未知状态：" + receipt.getStatus());
            waiting = waiting || "SUBMITTED".equals(receipt.getStatus());
        }
        if (waiting) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                OmsOrderSupplyReplanEntity locked = requireReplan(replanId, true);
                locked.setStatus("WAITING_PMS_REVIEW").setSupplyPhaseStatus("WAITING_PMS_REVIEW")
                        .setSupplyPhaseMessage("PMS采购订单行撤回申请已建立，等待采购异岗复核；复核后点击继续执行")
                        .setLastError(null);
                require(baseMapper.updateById(locked) == 1, "OMS等待采购复核状态保存失败");
                auditIfAbsent(locked, "OSR-SYS-PMS-WAIT-" + replanId, "WAIT_PMS_REVIEW",
                        "EXECUTING", "WAITING_PMS_REVIEW", locked.getSupplyPhaseMessage());
            });
            return PmsGateResult.WAITING;
        }
        if (!groups.isEmpty()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                OmsOrderSupplyReplanEntity locked = requireReplan(replanId, true);
                locked.setSupplyPhaseStatus("PENDING")
                        .setSupplyPhaseMessage("PMS采购订单行撤回已批准，继续撤回PLAN精确供应来源");
                require(baseMapper.updateById(locked) == 1, "PMS采购撤回完成状态保存失败");
            });
        }
        return PmsGateResult.READY;
    }

    private void markPmsTerminal(OmsOrderSupplyReplanEntity entity, PmsOmsSupplyWithdrawalDTO.Receipt receipt) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            OmsOrderSupplyReplanEntity locked = requireReplan(entity.getId(), true);
            String before = locked.getStatus();
            String message = limit("PMS采购撤回" + ("REJECTED".equals(receipt.getStatus()) ? "被驳回" : "证据已失效")
                    + "：" + receipt.getMessage(), 1000);
            locked.setStatus("STALE").setSupplyPhaseStatus("PENDING")
                    .setSupplyPhaseMessage(message).setLastError(message);
            require(baseMapper.updateById(locked) == 1, "OMS采购撤回终态保存失败");
            auditIfAbsent(locked, "OSR-SYS-PMS-END-" + entity.getId(), "PMS_REVIEW_TERMINATED",
                    before, "STALE", message);
        });
    }

    private void auditIfAbsent(OmsOrderSupplyReplanEntity entity, String requestId, String type,
                               String before, String after, String content) {
        if (operationMapper.selectCount(new LambdaQueryWrapper<OmsOrderSupplyReplanOperationEntity>()
                .eq(OmsOrderSupplyReplanOperationEntity::getRequestId, requestId)) == 0) {
            audit(entity, requestId, hash(requestId + '|' + after), type, before, after, content, context().user());
        }
    }

    private void executeSupply(Long replanId) {
        OmsOrderSupplyReplanEntity entity = requireReplan(replanId, false);
        for (OmsOrderSupplyReplanLineEntity line : lines(replanId)) {
            if (Set.of("SUCCEEDED", "NOT_APPLICABLE").contains(line.getSupplyStatus())) continue;
            String executionEvidence = line.getEvidenceFingerprint();
            String executionDownstreamEvidence = line.getDownstreamEvidenceFingerprint();
            if (line.getPmsOrderId() != null) {
                require("SUCCEEDED".equals(line.getPmsChangeStatus()),
                        "MRP[" + line.getMrpId() + "]关联采购订单尚未完成受控撤回");
                PlanMrpSupplyWithdrawalDTO.Evidence refreshed = mrpService.previewGeneratedSupplyWithdrawal(line.getMrpId());
                executionEvidence = refreshed.getEvidenceFingerprint();
                executionDownstreamEvidence = refreshed.getDownstreamEvidenceFingerprint();
                String finalExecutionEvidence = executionEvidence;
                String finalExecutionDownstreamEvidence = executionDownstreamEvidence;
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    OmsOrderSupplyReplanLineEntity locked = requireLine(line.getId(), true);
                    locked.setExecutionEvidenceFingerprint(finalExecutionEvidence)
                            .setExecutionDownstreamEvidenceFingerprint(finalExecutionDownstreamEvidence)
                            .setSupplyStatus("PENDING");
                    require(lineMapper.updateById(locked) == 1, "采购撤回后MRP执行证据保存失败");
                });
            }
            PlanMrpSupplyWithdrawalDTO.Command command = new PlanMrpSupplyWithdrawalDTO.Command()
                    .setMrpId(line.getMrpId()).setRequestId(limit("OSR-" + replanId + "-SUP-" + line.getMrpId(), 64))
                    .setExpectedEvidenceFingerprint(executionEvidence)
                    .setExpectedDownstreamEvidenceFingerprint(executionDownstreamEvidence)
                    .setReason(entity.getReason());
            PlanMrpSupplyWithdrawalDTO.Receipt receipt = mrpService.withdrawGeneratedSupply(command);
            require(Set.of("SUCCEEDED", "ALREADY_WITHDRAWN", "NOT_APPLICABLE").contains(receipt.getStatus()),
                    "MRP[" + line.getMrpId() + "]供应撤回失败：" + receipt.getMessage());
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                OmsOrderSupplyReplanLineEntity locked = requireLine(line.getId(), true);
                locked.setSupplyStatus("NOT_APPLICABLE".equals(receipt.getStatus()) ? "NOT_APPLICABLE" : "SUCCEEDED")
                        .setSupplyReceiptStatus(receipt.getStatus()).setSupplyMessage(limit(receipt.getMessage(), 500))
                        .setSupplyHandledTime(LocalDateTime.now());
                require(lineMapper.updateById(locked) == 1, "逐MRP供应撤回回执保存失败");
            });
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            OmsOrderSupplyReplanEntity locked = requireReplan(replanId, true);
            List<OmsOrderSupplyReplanLineEntity> current = lines(replanId);
            require(current.stream().allMatch(row -> Set.of("SUCCEEDED", "NOT_APPLICABLE").contains(row.getSupplyStatus())),
                    "仍有MRP供应未完成撤回");
            locked.setSupplyPhaseStatus("SUCCEEDED")
                    .setSupplyPhaseMessage("已按精确来源关闭" + current.stream().filter(row -> "SUCCEEDED".equals(row.getSupplyStatus())).count()
                            + "条未执行自制/采购/外协供应")
                    .setSupplyHandledTime(LocalDateTime.now());
            require(baseMapper.updateById(locked) == 1, "供应撤回阶段回执保存失败");
        });
    }

    private void executeReservationRelease(Long replanId) {
        OmsOrderSupplyReplanEntity entity = requireReplan(replanId, false);
        for (OmsOrderSupplyReplanLineEntity line : lines(replanId)) {
            if ("SUCCEEDED".equals(line.getReservationStatus())) continue;
            boolean released = mrpService.releaseForWithdrawal(line.getMrpId(),
                    limit("OSR-" + replanId + "-RES-" + line.getMrpId(), 54), entity.getReason());
            require(released, "MRP[" + line.getMrpId() + "]物料预留释放失败");
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                OmsOrderSupplyReplanLineEntity locked = requireLine(line.getId(), true);
                locked.setReservationStatus("SUCCEEDED").setReservationMessage("主料/替代料预留已释放，库存Pegging已转RELEASED")
                        .setReservationHandledTime(LocalDateTime.now());
                require(lineMapper.updateById(locked) == 1, "逐MRP预留释放回执保存失败");
            });
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            OmsOrderSupplyReplanEntity locked = requireReplan(replanId, true);
            require(lines(replanId).stream().allMatch(row -> "SUCCEEDED".equals(row.getReservationStatus())),
                    "仍有MRP物料预留未完成释放");
            locked.setReservationPhaseStatus("SUCCEEDED")
                    .setReservationPhaseMessage("全部主料/替代料预留和库存Pegging已安全释放")
                    .setReservationHandledTime(LocalDateTime.now()).setStatus("READY_FOR_FINAL_REVIEW");
            require(baseMapper.updateById(locked) == 1, "预留释放阶段回执保存失败");
        });
    }

    private void executeFinalReview(Long replanId) {
        OmsOrderSupplyReplanEntity entity = requireReplan(replanId, false);
        if ("SUCCEEDED".equals(entity.getStatus())) return;
        OmsOrderChangeVersionEntity observed = versionMapper.selectById(entity.getOrderChangeVersionId());
        require(observed != null && Set.of("SUBMITTED", "APPROVED").contains(observed.getStatus()),
                "订单变更版本已进入不允许续跑的终态");
        if ("SUBMITTED".equals(observed.getStatus())) {
            require("READY_FOR_FINAL_REVIEW".equals(entity.getStatus()), "供应撤回尚未完整收口，不能执行OMS最终评审");
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                OmsOrderSupplyReplanEntity locked = requireReplan(replanId, true);
                OmsOrderChangeVersionEntity version = versionMapper.selectOne(
                        new LambdaQueryWrapper<OmsOrderChangeVersionEntity>()
                                .eq(OmsOrderChangeVersionEntity::getId, locked.getOrderChangeVersionId()).last("FOR UPDATE"));
                require(version != null && "SUBMITTED".equals(version.getStatus()), "订单变更版本已终结，不能继续最终评审");
                changeLedger.rebaseAfterSupplyCascade(version, "OSR-SYS-REBASE-" + replanId,
                        changeLedger.currentSnapshot(locked.getOrderCode(), locked.getFactoryCode()),
                        "供应级联重排[" + locked.getReplanNo() + "]已撤回未执行供应并释放预留", context().user());
            });

            OmsOrderChangeReviewCommand command = new OmsOrderChangeReviewCommand();
            command.setReviewIds(List.of(entity.getReviewId()));
            command.setRequestId(limit("OSR-FINAL-" + replanId, 64));
            command.setReviewType("0");
            command.setRemark(limit("供应级联重排" + entity.getReplanNo() + "完成，批准订单变更并重算MRP："
                    + entity.getReviewReason(), 500));
            require(Boolean.TRUE.equals(orderReviewService.reviewChange(command)), "OMS最终评审及MRP重算未成功");
        }

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            OmsOrderSupplyReplanEntity locked = requireReplan(replanId, true);
            OmsOrderChangeVersionEntity version = versionMapper.selectById(locked.getOrderChangeVersionId());
            require(version != null && "APPROVED".equals(version.getStatus()), "OMS订单变更未进入批准终态");
            locked.setStatus("READY_FOR_REISSUE").setFinalReviewStatus("SUCCEEDED")
                    .setFinalReviewMessage("OMS订单、PLAN需求及MRP已按新数量/交期完成受控写回和重算")
                    .setFinalReviewHandledTime(LocalDateTime.now());
            require(baseMapper.updateById(locked) == 1, "OMS最终评审阶段回执保存失败");
        });
    }

    private void executeReissue(Long replanId) {
        OmsOrderSupplyReplanEntity entity = requireReplan(replanId, false);
        if ("SUCCEEDED".equals(entity.getStatus())) return;
        require("READY_FOR_REISSUE".equals(entity.getStatus()), "OMS最终评审尚未完成，不能重下达新MRP");
        List<String> demandNos = lines(replanId).stream().map(OmsOrderSupplyReplanLineEntity::getDemandNo)
                .filter(StrUtil::isNotBlank).distinct().sorted().toList();
        require(!demandNos.isEmpty(), "供应级联重排缺少PLAN需求范围");
        List<PlanMatMrpEntity> newMrp = mrpService.list(new LambdaQueryWrapper<PlanMatMrpEntity>()
                .in(PlanMatMrpEntity::getPlanDemandNo, demandNos).orderByAsc(PlanMatMrpEntity::getId));
        require(!newMrp.isEmpty(), "订单变更批准后没有形成新的MRP结果");
        boolean allIssued = newMrp.stream().allMatch(row -> Boolean.TRUE.equals(row.getStatus()));
        boolean noneIssued = newMrp.stream().noneMatch(row -> Boolean.TRUE.equals(row.getStatus()));
        require(allIssued || noneIssued, "新MRP处于部分下达状态，须先完成账实核查");
        if (!allIssued) {
            require(Boolean.TRUE.equals(mrpService.issueByIds(newMrp.stream().map(PlanMatMrpEntity::getId)
                    .toArray(Long[]::new))), "新MRP重下达失败");
        }
        List<PlanMatMrpEntity> issued = mrpService.list(new LambdaQueryWrapper<PlanMatMrpEntity>()
                .in(PlanMatMrpEntity::getId, newMrp.stream().map(PlanMatMrpEntity::getId).toList())
                .orderByAsc(PlanMatMrpEntity::getId));
        require(issued.size() == newMrp.size() && issued.stream().allMatch(row -> Boolean.TRUE.equals(row.getStatus())),
                "新MRP重下达后回读状态不完整");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            OmsOrderSupplyReplanEntity locked = requireReplan(replanId, true);
            locked.setStatus("SUCCEEDED").setReissuePhaseStatus("SUCCEEDED")
                    .setReissuePhaseMessage("新MRP已重下达" + issued.size() + "条，并重新形成库存预留及自制/采购/外协供应")
                    .setReissueHandledTime(LocalDateTime.now()).setCompletedTime(LocalDateTime.now())
                    .setResultSnapshot(JSONUtil.toJsonStr(Map.of("orderChangeVersionId", locked.getOrderChangeVersionId(),
                            "orderChangeStatus", "APPROVED", "newMrpIds", issued.stream().map(PlanMatMrpEntity::getId).toList(),
                            "lineCount", lines(replanId).size())));
            require(baseMapper.updateById(locked) == 1, "级联重排完成回执保存失败");
            audit(locked, "OSR-SYS-COMPLETE-" + replanId,
                    hash(replanId + "|SUCCEEDED"), "COMPLETE", "READY_FOR_REISSUE", "SUCCEEDED",
                    locked.getReissuePhaseMessage(), context().user());
        });
    }

    private void markFailed(Long replanId, Exception ex) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                OmsOrderSupplyReplanEntity entity = requireReplan(replanId, true);
                if ("SUCCEEDED".equals(entity.getStatus())) return;
                String before = entity.getStatus();
                entity.setStatus("FAILED").setLastError(limit(Objects.toString(ex.getMessage(), "级联重排执行失败"), 1000));
                require(baseMapper.updateById(entity) == 1, "级联重排失败状态保存失败");
                String requestId = "OSR-SYS-FAIL-" + replanId + '-' + Optional.ofNullable(entity.getRetryCount()).orElse(0);
                if (operationMapper.selectCount(new LambdaQueryWrapper<OmsOrderSupplyReplanOperationEntity>()
                        .eq(OmsOrderSupplyReplanOperationEntity::getRequestId, requestId)) == 0) {
                    audit(entity, requestId, hash(requestId + '|' + entity.getLastError()), "EXECUTE_FAILED",
                            before, "FAILED", entity.getLastError(), context().user());
                }
            });
        } catch (Exception ignored) {
            // 原始异常由永久阶段回执和下次续跑共同处理；不能用二次记账异常覆盖它。
        }
    }

    private boolean evidenceStillCurrent(OmsOrderSupplyReplanEntity entity) {
        OmsOrderBaseReviewEntity review = requireReview(entity.getReviewId(), true);
        OmsOrderChangeVersionEntity version = requireVersion(review, true);
        if (!Objects.equals(entity.getBeforeSnapshot(), changeLedger.currentSnapshot(entity.getOrderCode(), entity.getFactoryCode()))
                || !Objects.equals(entity.getProposedSnapshot(), changeLedger.proposalFromReview(candidateLines(entity.getOrderCode())))) {
            return false;
        }
        List<OmsOrderSupplyReplanLineEntity> frozen = lines(entity.getId());
        for (OmsOrderSupplyReplanLineEntity line : frozen.stream().filter(row -> row.getPmsOrderId() == null).toList()) {
            PlanMrpSupplyWithdrawalDTO.Evidence current = mrpService.previewGeneratedSupplyWithdrawal(line.getMrpId());
            if (!Objects.equals(line.getEvidenceFingerprint(), current.getEvidenceFingerprint())
                    || !Objects.equals(line.getDownstreamEvidenceFingerprint(),
                    current.getDownstreamEvidenceFingerprint())) return false;
        }
        Map<Long, List<OmsOrderSupplyReplanLineEntity>> pmsGroups = frozen.stream()
                .filter(row -> row.getPmsOrderId() != null)
                .collect(java.util.stream.Collectors.groupingBy(OmsOrderSupplyReplanLineEntity::getPmsOrderId));
        for (Map.Entry<Long, List<OmsOrderSupplyReplanLineEntity>> entry : pmsGroups.entrySet()) {
            List<OmsOrderSupplyReplanLineEntity> group = entry.getValue();
            for (OmsOrderSupplyReplanLineEntity line : group) {
                PlanMatMrpEntity mrp = mrpService.getById(line.getMrpId());
                if (mrp == null || !Objects.equals(mrp.getPurchaseOrderId(), line.getPmsOrderId())
                        || !Objects.equals(mrp.getPurchaseOrderDetailId(), line.getPmsOrderDetailId())
                        || !Objects.equals(mrp.getPurchaseOrderNo(), line.getPmsOrderNo())) return false;
            }
            PmsOrderChangeDTO.Preview preview = previewPms(entry.getKey(), group.stream()
                    .map(OmsOrderSupplyReplanLineEntity::getPmsOrderDetailId).distinct().sorted().toList());
            if (!Boolean.TRUE.equals(preview.getEligible()) || group.stream().anyMatch(line ->
                    !Objects.equals(line.getPmsEvidenceFingerprint(), preview.getEvidenceFingerprint()))) return false;
        }
        return Objects.equals(entity.getEvidenceFingerprint(), evidenceFingerprint(version, frozen.stream()
                .map(line -> new LineEvidence(line.getOrderDetailId(), line.getDemandId(), line.getDemandNo(),
                        new PlanMrpSupplyWithdrawalDTO.Evidence().setMrpId(line.getMrpId())
                                .setMaterialCode(line.getMaterialCode()).setSupplyType(line.getSupplyType())
                                .setSourceId(line.getSupplySourceId()).setSourceNo(line.getSupplySourceNo())
                                .setSourceQuantity(line.getSupplyQuantity()).setEvidenceFingerprint(line.getEvidenceFingerprint())
                                .setDownstreamEvidenceFingerprint(line.getDownstreamEvidenceFingerprint())
                                .setEvidenceSnapshot(line.getEvidenceSnapshot()), line.getPmsOrderId() == null ? null
                        : new PmsLink(line.getPmsOrderId(), line.getPmsOrderNo(), line.getPmsOrderDetailId(),
                        line.getPmsEvidenceFingerprint())))
                .toList()));
    }

    private List<LineEvidence> captureAffected(OmsOrderChangeVersionEntity version, OmsOrderBaseReviewEntity review,
                                               List<OmsOrderDetailsReviewEntity> candidates) {
        List<OmsOrderDetailsEntity> current = detailsService.list(new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                .eq(OmsOrderDetailsEntity::getOrderCode, review.getOrderCode()).orderByAsc(OmsOrderDetailsEntity::getId));
        Map<Long, OmsOrderDetailsEntity> byId = new HashMap<>();
        current.forEach(row -> byId.put(row.getId(), row));
        List<AffectedMrp> affected = new ArrayList<>();
        for (OmsOrderDetailsReviewEntity candidate : candidates) {
            if (candidate.getSourceDetailId() == null) continue;
            OmsOrderDetailsEntity source = byId.get(candidate.getSourceDetailId());
            require(source != null, "订单变更候选引用的来源交付行不存在");
            if (sameCore(source, candidate) || !OrderStateEnum.ISSUED_PLAN.getCode().equals(source.getState()))
                continue;
            List<PlnProductDemandEntity> demands = demandService.list(new LambdaQueryWrapper<PlnProductDemandEntity>()
                    .eq(PlnProductDemandEntity::getSaleOrderCode, review.getOrderCode())
                    .eq(PlnProductDemandEntity::getSaleOrderCodeDetails, source.getId()));
            require(demands.size() == 1, "订单行[" + source.getId() + "]缺少唯一PLAN来源需求");
            PlnProductDemandEntity demand = demands.get(0);
            require(Objects.equals(demand.getFactoryCode(), review.getFactoryCode()) && "2".equals(demand.getPlanState())
                            && optional(demand.getCompleteNum()).signum() == 0,
                    "订单行[" + source.getId() + "]对应需求不是未完成MRP状态，不能走开工前供应级联");
            List<PlanMatMrpEntity> mrps = mrpService.list(new LambdaQueryWrapper<PlanMatMrpEntity>()
                    .eq(PlanMatMrpEntity::getPlanDemandNo, demand.getDemandNo()).orderByAsc(PlanMatMrpEntity::getId));
            require(!mrps.isEmpty(), "PLAN需求[" + demand.getDemandNo() + "]缺少MRP明细");
            boolean formal = mrps.stream().anyMatch(row -> Boolean.TRUE.equals(row.getStatus())
                    || StrUtil.isNotBlank(row.getSupplySourceType()));
            if (!formal) continue;
            for (PlanMatMrpEntity mrp : mrps) {
                affected.add(new AffectedMrp(source.getId(), demand.getId(), demand.getDemandNo(), mrp));
            }
        }
        List<LineEvidence> result = new ArrayList<>();
        affected.stream().filter(item -> hasPurchaseOrder(item.mrp())).forEach(item -> {
            PlanMatMrpEntity mrp = item.mrp();
            require(mrp.getPurchaseOrderId() != null && mrp.getPurchaseOrderDetailId() != null
                            && StrUtil.isNotBlank(mrp.getPurchaseOrderNo())
                            && optional(mrp.getPurchaseOrderNum()).signum() > 0,
                    "MRP物料[" + mrp.getMatCode() + "]采购订单回执不完整，缺少精确订单、订单行、单号或数量");
        });
        Map<Long, List<AffectedMrp>> purchased = affected.stream().filter(item -> hasPurchaseOrder(item.mrp()))
                .collect(java.util.stream.Collectors.groupingBy(item -> item.mrp().getPurchaseOrderId(),
                        LinkedHashMap::new, java.util.stream.Collectors.toList()));
        for (Map.Entry<Long, List<AffectedMrp>> entry : purchased.entrySet()) {
            List<AffectedMrp> group = entry.getValue();
            List<Long> detailIds = group.stream().map(item -> item.mrp().getPurchaseOrderDetailId())
                    .distinct().sorted().toList();
            PmsOrderChangeDTO.Preview preview = previewPms(entry.getKey(), detailIds);
            require(Boolean.TRUE.equals(preview.getEligible()), "采购订单[" + preview.getPmsOrderNo()
                    + "]不能级联撤回：" + String.join("；", Optional.ofNullable(preview.getBlockers()).orElse(List.of())));
            require(Objects.equals(new TreeSet<>(detailIds), preview.getLines().stream()
                            .map(com.kitex.mom.pms.entity.PmsOrderChangeLineEntity::getOrderDetailId)
                            .collect(java.util.stream.Collectors.toCollection(TreeSet::new))),
                    "PMS撤回预览未精确返回全部目标采购订单行");
            Map<Long, BigDecimal> mrpQuantity = group.stream().collect(java.util.stream.Collectors.groupingBy(
                    item -> item.mrp().getPurchaseOrderDetailId(), java.util.stream.Collectors.reducing(BigDecimal.ZERO,
                            item -> optional(item.mrp().getPurchaseOrderNum()), BigDecimal::add)));
            preview.getLines().forEach(line -> require(optional(mrpQuantity.get(line.getOrderDetailId()))
                            .compareTo(optional(line.getCancelledQuantity())) == 0,
                    "采购订单行[" + line.getOrderDetailId() + "]可撤回量未被本次OMS变更的MRP完整覆盖，禁止整行误撤"));
            for (AffectedMrp item : group) {
                PlanMatMrpEntity mrp = item.mrp();
                String snapshot = JSONUtil.toJsonStr(Map.of(
                        "mrp", Arrays.asList(mrp.getId(), mrp.getPlanDemandNo(), mrp.getMatCode(), mrp.getNum(),
                                mrp.getSupplySourceType(), mrp.getSupplySourceId(), mrp.getSupplySourceNo(),
                                mrp.getPurchaseOrderId(), mrp.getPurchaseOrderNo(), mrp.getPurchaseOrderDetailId(),
                                mrp.getPurchaseOrderNum(), mrp.getPurchaseOrderStatus()),
                        "pmsEvidenceFingerprint", preview.getEvidenceFingerprint()));
                PlanMrpSupplyWithdrawalDTO.Evidence supply = new PlanMrpSupplyWithdrawalDTO.Evidence()
                        .setMrpId(mrp.getId()).setMaterialCode(mrp.getMatCode())
                        .setSupplyType(mrp.getSupplySourceType()).setSourceId(mrp.getSupplySourceId())
                        .setSourceNo(mrp.getSupplySourceNo()).setSourceQuantity(mrp.getSupplySourceQuantity())
                        .setSourceStatus(mrp.getSupplySourceStatus()).setStatus("ELIGIBLE_AFTER_PMS_REVIEW")
                        .setEvidenceFingerprint(hash(snapshot))
                        .setDownstreamEvidenceFingerprint(preview.getEvidenceFingerprint())
                        .setEvidenceSnapshot(snapshot).setMessage("须先完成PMS采购订单行异岗复核撤回");
                result.add(new LineEvidence(item.orderDetailId(), item.demandId(), item.demandNo(), supply,
                        new PmsLink(entry.getKey(), preview.getPmsOrderNo(), mrp.getPurchaseOrderDetailId(),
                                preview.getEvidenceFingerprint())));
            }
        }
        for (AffectedMrp item : affected.stream().filter(row -> !hasPurchaseOrder(row.mrp())).toList()) {
            PlanMatMrpEntity mrp = item.mrp();
            require(mrp.getPurchaseOrderId() == null && mrp.getPurchaseOrderDetailId() == null
                            && StrUtil.isBlank(mrp.getPurchaseOrderNo())
                            && optional(mrp.getPurchaseOrderNum()).signum() == 0,
                    "MRP物料[" + mrp.getMatCode() + "]采购订单回执不完整，须先完成账实治理");
            result.add(new LineEvidence(item.orderDetailId(), item.demandId(), item.demandNo(),
                    mrpService.previewGeneratedSupplyWithdrawal(mrp.getId()), null));
        }
        result.sort(Comparator.comparing(LineEvidence::demandNo).thenComparing(item -> item.supply().getMrpId()));
        return result;
    }

    private boolean hasPurchaseOrder(PlanMatMrpEntity mrp) {
        return mrp.getPurchaseOrderId() != null || mrp.getPurchaseOrderDetailId() != null
                || StrUtil.isNotBlank(mrp.getPurchaseOrderNo()) || optional(mrp.getPurchaseOrderNum()).signum() > 0;
    }

    private PmsOrderChangeDTO.Preview previewPms(Long orderId, List<Long> detailIds) {
        PmsOmsSupplyWithdrawalDTO.PreviewCommand command = new PmsOmsSupplyWithdrawalDTO.PreviewCommand()
                .setPmsOrderId(orderId).setOrderDetailIds(detailIds);
        R<PmsOrderChangeDTO.Preview> response = remotePmsPlanService.previewOmsLineWithdrawal(command);
        require(response != null && response.isOk() && response.getData() != null,
                "PMS采购订单行撤回预览不可用：" + (response == null ? "无响应" : response.getMsg()));
        return response.getData();
    }

    private String evidenceFingerprint(OmsOrderChangeVersionEntity version, List<LineEvidence> rows) {
        String facts = rows.stream().map(row -> row.orderDetailId() + "|" + row.demandId() + "|" + row.demandNo()
                        + "|" + row.supply().getMrpId() + "|" + row.supply().getEvidenceFingerprint()
                        + "|" + row.supply().getDownstreamEvidenceFingerprint())
                .reduce((left, right) -> left + ";" + right).orElse("");
        return hash(version.getId() + "|" + version.getBeforeSnapshot() + "|" + version.getProposedSnapshot() + "|" + facts);
    }

    private boolean sameCore(OmsOrderDetailsEntity source, OmsOrderDetailsReviewEntity candidate) {
        return Objects.equals(source.getProductCode(), candidate.getProductCode())
                && Objects.equals(source.getProductName(), candidate.getProductName())
                && Objects.equals(source.getProductSpec(), candidate.getProductSpec())
                && Objects.equals(source.getProductUnit(), candidate.getProductUnit())
                && Objects.equals(source.getDeliverTime(), candidate.getDeliverTime())
                && source.getDeliverNum() != null && candidate.getDeliverNum() != null
                && source.getDeliverNum().compareTo(candidate.getDeliverNum()) == 0;
    }

    private OmsOrderBaseReviewEntity requireReview(Long id, boolean lock) {
        LambdaQueryWrapper<OmsOrderBaseReviewEntity> query = new LambdaQueryWrapper<OmsOrderBaseReviewEntity>()
                .eq(OmsOrderBaseReviewEntity::getId, id);
        if (lock) query.last("FOR UPDATE");
        OmsOrderBaseReviewEntity review = reviewMapper.selectOne(query);
        require(review != null && "1".equals(review.getChangeType())
                        && OrderStateEnum.WAIT_REVIEW.getCode().equals(review.getOrderState()),
                "订单变更评审不存在或已不处于待评审状态");
        return review;
    }

    private OmsOrderChangeVersionEntity requireVersion(OmsOrderBaseReviewEntity review, boolean lock) {
        LambdaQueryWrapper<OmsOrderChangeVersionEntity> query = new LambdaQueryWrapper<OmsOrderChangeVersionEntity>()
                .eq(OmsOrderChangeVersionEntity::getReviewId, review.getId())
                .eq(OmsOrderChangeVersionEntity::getOrderCode, review.getOrderCode())
                .eq(OmsOrderChangeVersionEntity::getFactoryCode, review.getFactoryCode())
                .eq(OmsOrderChangeVersionEntity::getStatus, "SUBMITTED");
        if (lock) query.last("FOR UPDATE");
        OmsOrderChangeVersionEntity version = versionMapper.selectOne(query);
        require(version != null, "订单变更缺少本轮待评审版本");
        return version;
    }

    private List<OmsOrderDetailsReviewEntity> candidateLines(String orderCode) {
        List<OmsOrderDetailsReviewEntity> rows = detailsReviewService.list(
                new LambdaQueryWrapper<OmsOrderDetailsReviewEntity>()
                        .eq(OmsOrderDetailsReviewEntity::getOrderCode, orderCode)
                        .orderByAsc(OmsOrderDetailsReviewEntity::getId));
        require(!rows.isEmpty(), "订单变更候选交付行缺失");
        return rows;
    }

    private OmsOrderSupplyReplanEntity requireReplan(Long id, boolean lock) {
        require(id != null, "供应级联重排ID不能为空");
        LambdaQueryWrapper<OmsOrderSupplyReplanEntity> query = new LambdaQueryWrapper<OmsOrderSupplyReplanEntity>()
                .eq(OmsOrderSupplyReplanEntity::getId, id);
        if (lock) query.last("FOR UPDATE");
        OmsOrderSupplyReplanEntity entity = baseMapper.selectOne(query);
        require(entity != null, "供应级联重排不存在");
        return entity;
    }

    private OmsOrderSupplyReplanLineEntity requireLine(Long id, boolean lock) {
        LambdaQueryWrapper<OmsOrderSupplyReplanLineEntity> query = new LambdaQueryWrapper<OmsOrderSupplyReplanLineEntity>()
                .eq(OmsOrderSupplyReplanLineEntity::getId, id);
        if (lock) query.last("FOR UPDATE");
        OmsOrderSupplyReplanLineEntity line = lineMapper.selectOne(query);
        require(line != null, "供应级联逐MRP执行行不存在");
        return line;
    }

    private List<OmsOrderSupplyReplanLineEntity> lines(Long replanId) {
        return lineMapper.selectList(new LambdaQueryWrapper<OmsOrderSupplyReplanLineEntity>()
                .eq(OmsOrderSupplyReplanLineEntity::getReplanId, replanId)
                .orderByAsc(OmsOrderSupplyReplanLineEntity::getSequenceNo)
                .orderByAsc(OmsOrderSupplyReplanLineEntity::getId));
    }

    private OmsOrderSupplyReplanDTO.Detail detailOf(OmsOrderSupplyReplanEntity entity) {
        return new OmsOrderSupplyReplanDTO.Detail().setReplan(entity).setLines(lines(entity.getId()))
                .setOperations(operationMapper.selectList(
                        new LambdaQueryWrapper<OmsOrderSupplyReplanOperationEntity>()
                                .eq(OmsOrderSupplyReplanOperationEntity::getReplanId, entity.getId())
                                .orderByAsc(OmsOrderSupplyReplanOperationEntity::getOperationTime)
                                .orderByAsc(OmsOrderSupplyReplanOperationEntity::getId)));
    }

    private void audit(OmsOrderSupplyReplanEntity entity, String requestId, String fingerprint,
                       String type, String before, String after, String content, MomUser user) {
        OmsOrderSupplyReplanOperationEntity operation = new OmsOrderSupplyReplanOperationEntity()
                .setReplanId(entity.getId()).setRequestId(requestId).setRequestFingerprint(fingerprint)
                .setOperationType(type).setBeforeStatus(before).setAfterStatus(after).setContent(limit(content, 1000))
                .setOperatorCode(user.getUsername()).setOperatorName(userName(user))
                .setOperationTime(LocalDateTime.now()).setFactoryCode(entity.getFactoryCode());
        operation.setTenantId(entity.getTenantId());
        require(operationMapper.insert(operation) == 1, "供应级联重排不可变操作履历保存失败");
    }

    private Context context() {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        MomUser user = SecurityUtils.getUser();
        require(tenant != null && StrUtil.isNotBlank(factory) && user != null && StrUtil.isNotBlank(user.getUsername()),
                "供应级联重排必须在已登录、明确租户和factory-code工厂上下文中操作");
        return new Context(tenant, factory, user);
    }

    private void requireFactory(String factory) {
        require(StrUtil.isNotBlank(factory) && Objects.equals(factory, FactoryContextHolder.getFactoryCode()),
                "当前工厂无权查看或操作该供应级联重排");
    }

    private String text(String value, int max, String label) {
        String result = value == null ? null : value.trim();
        require(StrUtil.isNotBlank(result) && result.length() <= max, label + "不能为空且不能超过" + max + "字");
        return result;
    }

    private String limit(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private String userName(MomUser user) {
        return StrUtil.blankToDefault(user.getName(), user.getUsername());
    }

    private BigDecimal optional(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("供应级联重排证据指纹生成失败");
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    private enum PmsGateResult {
        READY, WAITING, TERMINAL
    }

    private record Context(Long tenantId, String factory, MomUser user) {
    }

    private record LineEvidence(Long orderDetailId, Long demandId, String demandNo,
                                PlanMrpSupplyWithdrawalDTO.Evidence supply, PmsLink pms) {
    }

    private record AffectedMrp(Long orderDetailId, Long demandId, String demandNo, PlanMatMrpEntity mrp) {
    }

    private record PmsLink(Long orderId, String orderNo, Long orderDetailId, String evidenceFingerprint) {
    }
}
