package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.MesPlanWithdrawalDTO;
import com.kitex.mom.plan.constant.IssueStatusEnum;
import com.kitex.mom.plan.dto.PlanMrpSupplyWithdrawalDTO;
import com.kitex.mom.plan.dto.PlnIssueChangeDTO;
import com.kitex.mom.plan.entity.PlanMatMrpEntity;
import com.kitex.mom.plan.entity.PlnIssueChangeEntity;
import com.kitex.mom.plan.entity.PlnIssueChangeOperationEntity;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnProductDemandChangeEntity;
import com.kitex.mom.plan.entity.PlnProductDemandEntity;
import com.kitex.mom.plan.entity.PlnSchedulingBatchEntity;
import com.kitex.mom.plan.entity.PlnSchedulingBatchAllocationEntity;
import com.kitex.mom.plan.entity.PlnSchedulingOperationEntity;
import com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity;
import com.kitex.mom.plan.mapper.PlanMatMrpMapper;
import com.kitex.mom.plan.mapper.PlnIssueChangeMapper;
import com.kitex.mom.plan.mapper.PlnIssueChangeOperationMapper;
import com.kitex.mom.plan.mapper.PlnIssueMapper;
import com.kitex.mom.plan.mapper.PlnProductDemandChangeMapper;
import com.kitex.mom.plan.mapper.PlnProductDemandMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingBatchAllocationMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingBatchMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingOperationMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingTaskDetailMapper;
import com.kitex.mom.plan.service.PlanMatMrpService;
import com.kitex.mom.plan.service.PlnIssueChangeService;
import com.kitex.mom.plan.service.PlnProductDemandService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 已排产/已下达生产计划的通用受控变更编排。
 *
 * <p>当前白名单覆盖无OMS、质量补产及已发布MPS权威来源的内部产品需求。外部来源必须由来源模块发起，
 * 避免PLAN单独改写订单或MPS。流程只允许无现场执行事实的计划，批准后按“下层供应→MES→WMS→APS→PLAN/MRP”
 * 依次收口；跨服务命令使用永久请求号，失败后可安全续跑。</p>
 */
@Service
@RequiredArgsConstructor
public class PlnIssueChangeServiceImpl extends ServiceImpl<PlnIssueChangeMapper, PlnIssueChangeEntity>
        implements PlnIssueChangeService {

    private static final Set<String> LIVE = Set.of("SUBMITTED", "APPROVED", "EXECUTING", "PARTIAL", "FAILED");

    private final PlnIssueChangeOperationMapper operationMapper;
    private final PlnIssueMapper issueMapper;
    private final PlnProductDemandMapper demandMapper;
    private final PlnProductDemandChangeMapper demandChangeMapper;
    private final PlanMatMrpMapper mrpMapper;
    private final PlnSchedulingOperationMapper operationSchedulingMapper;
    private final PlnSchedulingTaskDetailMapper taskDetailMapper;
    private final PlnSchedulingBatchAllocationMapper batchAllocationMapper;
    private final PlnSchedulingBatchMapper batchMapper;
    private final PlanMatMrpService mrpService;
    private final PlnProductDemandService demandService;
    private final PlnMpsService mpsService;
    private final RemoteMesTaskService remoteMesTaskService;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnIssueChangeEntity submit(PlnIssueChangeDTO.SubmitCommand command) {
        validateTarget(command);
        String requestId = requestId(command.getRequestId(), "变更申请请求号");
        String reason = text(command.getReason(), 500, "变更原因");
        String requestFingerprint = hash(command.getIssueId() + "|" + quantity(command.getTargetQuantity())
                + "|" + command.getTargetDemandDate() + "|" + reason);
        baseMapper.lockKey("REQUEST:" + requestId);
        PlnIssueChangeEntity replay = baseMapper.selectOne(new LambdaQueryWrapper<PlnIssueChangeEntity>()
                .eq(PlnIssueChangeEntity::getSubmitRequestId, requestId));
        if (replay != null) {
            require(Objects.equals(replay.getRequestFingerprint(), requestFingerprint), "变更申请幂等号已被不同请求使用");
            requireFactory(replay.getFactoryCode());
            return replay;
        }
        require(operationByRequest(requestId) == null, "变更申请请求号已用于其他操作");
        baseMapper.lockKey("ISSUE:" + command.getIssueId());
        PlnIssueEntity issue = requireIssue(command.getIssueId(), true);
        require(baseMapper.selectCount(new LambdaQueryWrapper<PlnIssueChangeEntity>()
                        .eq(PlnIssueChangeEntity::getIssueId, issue.getId())
                        .in(PlnIssueChangeEntity::getStatus, LIVE)) == 0,
                "该生产计划已有待复核或可续跑的变更单");
        Evidence evidence = capture(issue, command.getTargetQuantity(), command.getTargetDemandDate(), true, null);
        MomUser user = currentUser();
        PlnIssueChangeEntity entity = new PlnIssueChangeEntity()
                .setChangeNo("PIC-" + IdUtil.getSnowflakeNextIdStr()).setSubmitRequestId(requestId)
                .setRequestFingerprint(requestFingerprint).setIssueId(issue.getId()).setIssueNo(issue.getIssueNo())
                .setDemandId(evidence.demand().getId()).setDemandNo(evidence.demand().getDemandNo())
                .setProductCode(issue.getProductCode()).setBeforeQuantity(issue.getNum())
                .setBeforeDemandDate(evidence.demand().getPlanEndDate()).setTargetQuantity(command.getTargetQuantity())
                .setTargetDemandDate(command.getTargetDemandDate()).setEvidenceFingerprint(evidence.combinedFingerprint())
                .setLocalEvidenceFingerprint(evidence.localFingerprint())
                .setSupplyEvidenceFingerprint(evidence.supplyFingerprint())
                .setMesEvidenceFingerprint(evidence.mesFingerprint())
                .setSupplyEvidenceSnapshot(json(evidence.supplyEvidence())).setEvidenceSnapshot(evidence.snapshot())
                .setReason(reason).setStatus("SUBMITTED").setApplicantCode(user.getUsername())
                .setApplicantName(userName(user)).setSubmittedTime(LocalDateTime.now()).setRetryCount(0)
                .setSupplyPhaseStatus("PENDING").setMesPhaseStatus("PENDING").setWmsPhaseStatus("PENDING")
                .setApsPhaseStatus("PENDING").setPlanPhaseStatus("PENDING").setFactoryCode(issue.getFactoryCode());
        require(baseMapper.insert(entity) == 1, "生产计划变更申请保存失败");
        audit(entity, requestId, requestFingerprint, "SUBMIT", null, "SUBMITTED", reason, user);
        return entity;
    }

    @Override
    public PlnIssueChangeEntity review(PlnIssueChangeDTO.ReviewCommand command) {
        require(command != null && command.getChangeId() != null && command.getApproved() != null
                        && StrUtil.isNotBlank(command.getRequestId())
                        && StrUtil.isNotBlank(command.getExpectedEvidenceFingerprint())
                        && StrUtil.isNotBlank(command.getReason()), "生产计划变更复核参数不完整");
        String requestId = requestId(command.getRequestId(), "变更复核请求号");
        String reason = text(command.getReason(), 500, "复核说明");
        String expected = fingerprint(command.getExpectedEvidenceFingerprint());
        String requestFingerprint = hash(command.getChangeId() + "|" + command.getApproved() + "|" + expected + "|" + reason);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        PlnIssueChangeEntity decision = tx.execute(status -> {
            baseMapper.lockKey("REQUEST:" + requestId);
            PlnIssueChangeOperationEntity replay = operationByRequest(requestId);
            if (replay != null) {
                require(Objects.equals(replay.getChangeId(), command.getChangeId())
                                && Objects.equals(replay.getRequestFingerprint(), requestFingerprint),
                        "变更复核幂等号已被不同请求使用");
                PlnIssueChangeEntity replayed = requireChange(command.getChangeId(), false);
                requireFactory(replayed.getFactoryCode());
                return replayed;
            }
            require(changeBySubmitRequest(requestId) == null, "变更复核请求号已用于变更申请");
            PlnIssueChangeEntity entity = requireChange(command.getChangeId(), true);
            requireFactory(entity.getFactoryCode());
            require("SUBMITTED".equals(entity.getStatus()), "仅待复核生产计划变更允许审批");
            require(Objects.equals(entity.getEvidenceFingerprint(), expected), "变更申请证据指纹不一致，请刷新后重试");
            MomUser user = currentUser();
            require(!Objects.equals(entity.getApplicantCode(), user.getUsername()), "变更申请人与复核人必须分离");
            String before = entity.getStatus();
            if (!Boolean.TRUE.equals(command.getApproved())) {
                entity.setStatus("REJECTED").setApproved(false);
            } else {
                PlnIssueEntity issue = requireIssue(entity.getIssueId(), true);
                Evidence current = capture(issue, entity.getTargetQuantity(), entity.getTargetDemandDate(), true, null);
                if (!Objects.equals(entity.getEvidenceFingerprint(), current.combinedFingerprint())) {
                    entity.setStatus("STALE").setApproved(false).setLastError("PLAN、MRP、APS或MES事实已变化，请重新申请");
                } else {
                    entity.setStatus("APPROVED").setApproved(true).setExecutionRequestId("PIC-EXEC-" + entity.getId());
                }
            }
            entity.setReviewRequestId(requestId).setReviewReason(reason).setReviewerCode(user.getUsername())
                    .setReviewerName(userName(user)).setReviewedTime(LocalDateTime.now());
            require(baseMapper.updateById(entity) == 1, "生产计划变更复核回执保存失败");
            String operationType = Boolean.TRUE.equals(command.getApproved()) ? "REVIEW_APPROVE" : "REVIEW_REJECT";
            if ("STALE".equals(entity.getStatus())) operationType = "REVIEW_STALE";
            audit(entity, requestId, requestFingerprint, operationType, before, entity.getStatus(), reason, user);
            return entity;
        });
        if (decision != null && "APPROVED".equals(decision.getStatus())) execute(decision.getId());
        return requireChange(command.getChangeId(), false);
    }

    @Override
    public PlnIssueChangeEntity retry(PlnIssueChangeDTO.RetryCommand command) {
        require(command != null && command.getChangeId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getReason()), "变更续跑必须提供变更单、请求号和原因");
        String requestId = requestId(command.getRequestId(), "变更续跑请求号");
        String reason = text(command.getReason(), 500, "续跑原因");
        String requestFingerprint = hash(command.getChangeId() + "|" + reason);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        PlnIssueChangeEntity entity = tx.execute(status -> {
            baseMapper.lockKey("REQUEST:" + requestId);
            PlnIssueChangeOperationEntity replay = operationByRequest(requestId);
            if (replay != null) {
                require(Objects.equals(replay.getChangeId(), command.getChangeId())
                                && Objects.equals(replay.getRequestFingerprint(), requestFingerprint),
                        "变更续跑幂等号已被不同请求使用");
                PlnIssueChangeEntity replayed = requireChange(command.getChangeId(), false);
                requireFactory(replayed.getFactoryCode());
                return replayed;
            }
            require(changeBySubmitRequest(requestId) == null, "变更续跑请求号已用于变更申请");
            PlnIssueChangeEntity locked = requireChange(command.getChangeId(), true);
            requireFactory(locked.getFactoryCode());
            require(Set.of("FAILED", "PARTIAL").contains(locked.getStatus()), "仅失败或部分完成的变更允许续跑");
            String before = locked.getStatus();
            locked.setStatus("APPROVED").setLastError(null)
                    .setRetryCount(Optional.ofNullable(locked.getRetryCount()).orElse(0) + 1);
            require(baseMapper.updateById(locked) == 1, "生产计划变更续跑状态保存失败");
            MomUser user = currentUser();
            audit(locked, requestId, requestFingerprint, "RETRY", before, "APPROVED", reason, user);
            return locked;
        });
        if (entity != null && "APPROVED".equals(entity.getStatus())) execute(entity.getId());
        return requireChange(command.getChangeId(), false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnIssueChangeEntity withdraw(PlnIssueChangeDTO.WithdrawCommand command) {
        require(command != null && command.getChangeId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getReason()), "撤回必须提供变更单、请求号和原因");
        String requestId = requestId(command.getRequestId(), "变更撤回请求号");
        String reason = text(command.getReason(), 500, "撤回原因");
        String requestFingerprint = hash(command.getChangeId() + "|" + reason);
        baseMapper.lockKey("REQUEST:" + requestId);
        PlnIssueChangeOperationEntity replay = operationByRequest(requestId);
        if (replay != null) {
            require(Objects.equals(replay.getChangeId(), command.getChangeId())
                            && Objects.equals(replay.getRequestFingerprint(), requestFingerprint),
                    "变更撤回幂等号已被不同请求使用");
            PlnIssueChangeEntity replayed = requireChange(command.getChangeId(), false);
            requireFactory(replayed.getFactoryCode());
            return replayed;
        }
        require(changeBySubmitRequest(requestId) == null, "变更撤回请求号已用于变更申请");
        PlnIssueChangeEntity entity = requireChange(command.getChangeId(), true);
        requireFactory(entity.getFactoryCode());
        require("SUBMITTED".equals(entity.getStatus()), "仅待复核生产计划变更允许申请人撤回");
        MomUser user = currentUser();
        require(Objects.equals(entity.getApplicantCode(), user.getUsername()), "仅申请人可以撤回待复核生产计划变更");
        entity.setStatus("WITHDRAWN");
        require(baseMapper.updateById(entity) == 1, "生产计划变更撤回失败");
        audit(entity, requestId, requestFingerprint, "WITHDRAW", "SUBMITTED", "WITHDRAWN", reason, user);
        return entity;
    }

    private void execute(Long changeId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        try {
            tx.executeWithoutResult(status -> executeInTransaction(changeId));
        } catch (RuntimeException ex) {
            TransactionTemplate failure = new TransactionTemplate(transactionManager);
            failure.executeWithoutResult(status -> {
                PlnIssueChangeEntity entity = requireChange(changeId, true);
                if ("SUCCEEDED".equals(entity.getStatus())) return;
                entity.setStatus(anySucceeded(entity) ? "PARTIAL" : "FAILED")
                        .setLastError(limit(Objects.toString(ex.getMessage(), "生产计划变更执行失败"), 1000));
                require(baseMapper.updateById(entity) == 1, "生产计划变更失败回执保存失败");
            });
        }
    }

    private void executeInTransaction(Long changeId) {
        PlnIssueChangeEntity entity = requireChange(changeId, true);
        if ("SUCCEEDED".equals(entity.getStatus())) return;
        require("APPROVED".equals(entity.getStatus()), "生产计划变更尚未审批或不允许执行");
        PlnIssueEntity issue = requireIssue(entity.getIssueId(), true);
        Evidence current = capture(issue, entity.getTargetQuantity(), entity.getTargetDemandDate(), false,
                entity.getSupplyEvidenceSnapshot());
        require(Objects.equals(entity.getLocalEvidenceFingerprint(), current.localFingerprint()),
                "变更执行前PLAN/MRP/APS本地事实已变化，请重新申请");
        require(Objects.equals(entity.getSupplyEvidenceFingerprint(), current.supplyFingerprint()),
                "变更执行前下层供应事实已变化，请重新申请");
        entity.setStatus("EXECUTING").setLastError(null);
        require(baseMapper.updateById(entity) == 1, "生产计划变更执行状态保存失败");

        int withdrawnSupplyCount = 0;
        for (PlanMrpSupplyWithdrawalDTO.Evidence evidence : current.supplyEvidence()) {
            if (!"ELIGIBLE".equals(evidence.getStatus())) continue;
            PlanMrpSupplyWithdrawalDTO.Receipt receipt = mrpService.withdrawGeneratedSupply(
                    new PlanMrpSupplyWithdrawalDTO.Command().setMrpId(evidence.getMrpId())
                            .setRequestId(limit("PIC-" + entity.getId() + "-SUP-" + evidence.getMrpId(), 64))
                            .setExpectedEvidenceFingerprint(evidence.getEvidenceFingerprint())
                            .setExpectedDownstreamEvidenceFingerprint(evidence.getDownstreamEvidenceFingerprint())
                            .setReason(entity.getReason()));
            require(Set.of("SUCCEEDED", "ALREADY_WITHDRAWN", "NOT_APPLICABLE").contains(receipt.getStatus()),
                    "MRP[" + evidence.getMrpId() + "]下层供应撤回失败：" + receipt.getMessage());
            if (!"NOT_APPLICABLE".equals(receipt.getStatus())) withdrawnSupplyCount++;
        }
        entity.setSupplyPhaseStatus("SUCCEEDED").setSupplyPhaseMessage("已核验" + current.supplyEvidence().size()
                        + "条MRP并撤回" + withdrawnSupplyCount + "条未执行下层供应")
                .setSupplyHandledTime(LocalDateTime.now());
        require(baseMapper.updateById(entity) == 1, "生产计划变更供应阶段回执保存失败");

        if (IssueStatusEnum.ISSUED.getCode().equals(issue.getStatus())) {
            MesPlanWithdrawalDTO.Command command = mesCommand(entity, issue, false, entity.getMesEvidenceFingerprint());
            R<MesPlanWithdrawalDTO.Receipt> response = remoteMesTaskService.withdrawPlan(command);
            require(response != null && response.isOk() && response.getData() != null
                            && Set.of("SUCCEEDED", "ALREADY_WITHDRAWN").contains(response.getData().getStatus())
                            && Objects.equals(response.getData().getEvidenceFingerprint(), entity.getMesEvidenceFingerprint()),
                    "MES计划撤回失败：" + (response == null ? "无响应" : response.getMsg()));
            entity.setMesPhaseStatus("SUCCEEDED").setMesPhaseMessage(response.getData().getMessage());
        } else {
            entity.setMesPhaseStatus("NOT_APPLICABLE").setMesPhaseMessage("计划尚未下达MES");
        }
        entity.setMesHandledTime(LocalDateTime.now());
        require(baseMapper.updateById(entity) == 1, "生产计划变更MES阶段回执保存失败");

        List<PlanMatMrpEntity> mrpRows = mrpRows(entity.getDemandNo(), true);
        for (PlanMatMrpEntity row : mrpRows) {
            require(Boolean.TRUE.equals(mrpService.releaseForWithdrawal(row.getId(),
                    limit("PIC-" + entity.getId() + "-MRP-" + row.getId(), 64), entity.getReason())),
                    "MRP[" + row.getId() + "]库存预留释放失败");
        }
        entity.setWmsPhaseStatus("SUCCEEDED").setWmsPhaseMessage("已释放主料/替代料预留并同步库存Pegging")
                .setWmsHandledTime(LocalDateTime.now());
        require(baseMapper.updateById(entity) == 1, "生产计划变更WMS阶段回执保存失败");

        unlockAps(issue);
        entity.setApsPhaseStatus("SUCCEEDED").setApsPhaseMessage("已解锁并关闭原计划独占APS批次、任务及工序占用")
                .setApsHandledTime(LocalDateTime.now());
        require(baseMapper.updateById(entity) == 1, "生产计划变更APS阶段回执保存失败");

        issue.setStatus(IssueStatusEnum.NOT_ISSUED.getCode());
        issue.setLockStatus(false);
        issue.setLockTaskId(null);
        issue.setLockTime(null);
        issue.setScheduledDate(null);
        issue.setScheduledEndDate(null);
        issue.setRemark(limit(StrUtil.blankToDefault(issue.getRemark(), "") + "；受控变更" + entity.getChangeNo()
                + "关闭原计划，等待新MRP重新排产", 500));
        require(issueMapper.updateById(issue) == 1, "原生产计划历史版本关闭失败");
        PlnProductDemandEntity demand = requireDemand(entity.getDemandId(), true);
        require(Objects.equals(demand.getDemandNo(), entity.getDemandNo()), "生产计划来源需求已变化");
        demand.setNeedNum(entity.getTargetQuantity());
        demand.setPlanEndDate(entity.getTargetDemandDate());
        demand.setPlanState("1");
        require(demandMapper.updateById(demand) == 1, "目标产品需求写回失败");
        R<?> mrpResult = demandService.mrp(new Long[]{demand.getId()});
        require(mrpResult != null && mrpResult.isOk(), "目标需求MRP重算失败");
        PlnProductDemandEntity recalculated = requireDemand(demand.getId(), true);
        List<PlanMatMrpEntity> newMrp = mrpRows(demand.getDemandNo(), false);
        require("2".equals(recalculated.getPlanState()) && !newMrp.isEmpty(), "目标需求未形成有效MRP结果");
        entity.setPlanPhaseStatus("SUCCEEDED")
                .setPlanPhaseMessage("原计划已关闭，目标需求与MRP已重算；进入待排产、锁定和重新下达")
                .setPlanHandledTime(LocalDateTime.now()).setStatus("SUCCEEDED").setCompletedTime(LocalDateTime.now())
                .setResultSnapshot(json(Map.of("oldIssueId", issue.getId(), "oldIssueStatus", issue.getStatus(),
                        "demandId", recalculated.getId(), "demandState", recalculated.getPlanState(),
                        "targetQuantity", entity.getTargetQuantity(), "targetDemandDate", entity.getTargetDemandDate(),
                        "newMrpIds", newMrp.stream().map(PlanMatMrpEntity::getId).toList(),
                        "nextAction", "REPLAN_LOCK_AND_REISSUE")));
        require(baseMapper.updateById(entity) == 1, "生产计划变更终态回执保存失败");
    }

    private Evidence capture(PlnIssueEntity issue, BigDecimal targetQuantity, LocalDate targetDemandDate,
                             boolean previewRemote, String frozenSupplySnapshot) {
        requireFactory(issue.getFactoryCode());
        require(Set.of(IssueStatusEnum.UNISSUED.getCode(), IssueStatusEnum.ISSUED.getCode()).contains(issue.getStatus()),
                "仅已排产未下达或已下达但未执行的计划允许受控变更");
        require("issue".equals(issue.getType()), "仅正式排产形成的根生产计划允许受控变更，下层供应或其他来源计划须走来源流程");
        require(!"QUALITY_SUPPLEMENT".equals(issue.getPlanType()), "质量补产计划必须走补产撤回流程");
        require(zero(issue.getFinishNum()) && zero(issue.getUnqualifiedNum()) && zero(issue.getQualityIsolatedNum())
                        && zero(issue.getQualityReleasedNum()) && zero(issue.getQualityRejectedNum()) && zero(issue.getScrapNum())
                        && zero(issue.getReworkNum()) && zero(issue.getReworkCompletedNum())
                        && zero(issue.getReworkQualifiedNum()) && zero(issue.getReworkRejectedNum()),
                "生产计划已有报工或质量处置事实，必须走现场停止、退料和冲销流程");
        require(targetQuantity != null && targetQuantity.signum() > 0 && targetDemandDate != null
                        && !targetDemandDate.isBefore(LocalDate.now()), "目标数量必须大于0且目标交期不能早于今天");
        require(issue.getNum() != null && (issue.getNum().compareTo(targetQuantity) != 0
                        || !Objects.equals(targetDemandDate, issue.getDemandTime())), "目标数量和交期均未变化");
        List<PlnProductDemandEntity> demands = demandMapper.selectList(new LambdaQueryWrapper<PlnProductDemandEntity>()
                .eq(PlnProductDemandEntity::getDemandNo, issue.getIssueNo())
                .eq(PlnProductDemandEntity::getProductCode, issue.getProductCode()).orderByAsc(PlnProductDemandEntity::getId));
        require(demands.size() == 1, "生产计划必须精确关联一条同产品的产品需求");
        // 与需求变更、MRP重算共用产品需求行锁，避免两条受控流程同时冻结出互相矛盾的证据。
        PlnProductDemandEntity demand = requireDemand(demands.get(0).getId(), true);
        require(demandChangeMapper.selectCount(new LambdaQueryWrapper<PlnProductDemandChangeEntity>()
                        .eq(PlnProductDemandChangeEntity::getDemandId, demand.getId())
                        .eq(PlnProductDemandChangeEntity::getStatus, "SUBMITTED")) == 0,
                "产品需求已有待复核变更，请先完成或取消需求变更后再调整生产计划");
        require(Objects.equals(demand.getFactoryCode(), issue.getFactoryCode()) && "2".equals(demand.getPlanState())
                        && zero(demand.getCompleteNum()) && demand.getNeedNum() != null
                        && demand.getNeedNum().compareTo(issue.getNum()) == 0
                        && demand.getPlanEndDate() != null
                        && Objects.equals(demand.getPlanEndDate(), issue.getDemandTime()),
                "产品需求与生产计划的工厂、数量、交期、状态或完成事实不一致");
        require(StrUtil.isAllBlank(demand.getSaleOrderCode(), demand.getSourceType(), demand.getSourceNo())
                        && demand.getSourceId() == null,
                "OMS或其他权威来源需求必须从来源模块发起受控变更");
        require(!mpsService.hasActiveLine(demand.getId()), "已发布MPS需求必须先走MPS版本修订，不能直接改变生产计划");
        List<PlanMatMrpEntity> mrpRows = mrpRows(demand.getDemandNo(), false);
        require(!mrpRows.isEmpty(), "产品需求缺少可核验的MRP明细");
        require(mrpRows.stream().allMatch(row -> Objects.equals(row.getFactoryCode(), issue.getFactoryCode())
                        && zero(row.getCompleteNum()) && row.getPurchaseOrderId() == null
                        && StrUtil.isBlank(row.getPurchaseOrderNo())),
                "MRP已形成完成量或采购订单，必须先走PMS订单/现场受控变更");

        Map<Long, PlanMrpSupplyWithdrawalDTO.Evidence> frozen = new HashMap<>();
        if (frozenSupplySnapshot != null) {
            for (PlanMrpSupplyWithdrawalDTO.Evidence row : JSONUtil.parseArray(frozenSupplySnapshot)
                    .toList(PlanMrpSupplyWithdrawalDTO.Evidence.class)) {
                require(row.getMrpId() != null && frozen.putIfAbsent(row.getMrpId(), row) == null,
                        "冻结的MRP下层供应清单包含重复或空来源");
            }
            require(frozen.size() == mrpRows.size(), "冻结的MRP下层供应清单与当前MRP数量不一致");
        }
        List<PlanMrpSupplyWithdrawalDTO.Evidence> supplies = new ArrayList<>();
        for (PlanMatMrpEntity row : mrpRows) {
            if (frozenSupplySnapshot == null) {
                supplies.add(mrpService.previewGeneratedSupplyWithdrawal(row.getId()));
            } else {
                PlanMrpSupplyWithdrawalDTO.Evidence expected = frozen.get(row.getId());
                require(expected != null, "MRP[" + row.getId() + "]不在冻结供应清单中");
                PlanMrpSupplyWithdrawalDTO.Evidence current = mrpService.captureGeneratedSupplyWithdrawalForExecution(
                        row.getId(), expected.getDownstreamEvidenceFingerprint());
                require(Objects.equals(current.getEvidenceFingerprint(), expected.getEvidenceFingerprint()),
                        "MRP[" + row.getId() + "]下层供应本地事实已变化");
                supplies.add(current);
            }
        }
        List<PlnSchedulingOperationEntity> operations = operationSchedulingMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingOperationEntity>().eq(PlnSchedulingOperationEntity::getIssueId, issue.getId())
                        .orderByAsc(PlnSchedulingOperationEntity::getOperationSeq).orderByAsc(PlnSchedulingOperationEntity::getId));
        List<PlnSchedulingTaskDetailEntity> details = taskDetailMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingTaskDetailEntity>().eq(PlnSchedulingTaskDetailEntity::getIssueId, issue.getId())
                        .orderByAsc(PlnSchedulingTaskDetailEntity::getId));
        List<PlnSchedulingBatchAllocationEntity> allocations = batchAllocationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity>()
                        .eq(PlnSchedulingBatchAllocationEntity::getIssueId, issue.getId())
                        .orderByAsc(PlnSchedulingBatchAllocationEntity::getBatchId)
                        .orderByAsc(PlnSchedulingBatchAllocationEntity::getId));
        for (PlnSchedulingBatchAllocationEntity allocation : allocations) {
            require(batchAllocationMapper.selectCount(new LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity>()
                    .eq(PlnSchedulingBatchAllocationEntity::getBatchId, allocation.getBatchId())) == 1,
                    "APS批次[" + allocation.getBatchNo() + "]包含其他计划，须先拆批/重排");
        }
        if (Boolean.TRUE.equals(issue.getLockStatus())) {
            require(issue.getLockTaskId() != null && !operations.isEmpty() && !details.isEmpty() && !allocations.isEmpty()
                            && operations.stream().allMatch(row -> Boolean.TRUE.equals(row.getLockStatus()))
                            && details.stream().allMatch(row -> Boolean.TRUE.equals(row.getLockStatus())),
                    "计划锁定但APS工序、任务或批次来源不完整");
        }
        Map<String, Object> local = new LinkedHashMap<>();
        local.put("issue", Arrays.asList(issue.getId(), issue.getIssueNo(), issue.getProductCode(), issue.getNum(), issue.getDemandTime(),
                issue.getStatus(), issue.getLockStatus(), issue.getLockTaskId(), issue.getScheduledDate(), issue.getScheduledEndDate()));
        local.put("demand", Arrays.asList(demand.getId(), demand.getDemandNo(), demand.getNeedNum(), demand.getPlanEndDate(),
                demand.getPlanState(), demand.getDemandWarehouseCode(), demand.getMrpPriority()));
        local.put("mrp", mrpRows.stream().map(row -> Arrays.asList(row.getId(), row.getMatCode(), row.getNum(), row.getStatus(),
                row.getSupplySourceType(), row.getSupplySourceId(), row.getSupplySourceNo(), row.getSupplySourceStatus(),
                row.getMainMaterialReservationStatus(), row.getMaterialReservationStatus(), row.getInventoryPeggingStatus())).toList());
        local.put("operations", operations.stream().map(row -> Arrays.asList(row.getId(), row.getTaskId(), row.getProcessCode(),
                row.getScheduledStartTime(), row.getScheduledEndTime(), row.getLockStatus(), row.getSchedulingBatchId())).toList());
        local.put("details", details.stream().map(row -> Arrays.asList(row.getId(), row.getTaskId(), row.getNum(),
                row.getLockStatus(), row.getOperationScheduleStatus())).toList());
        local.put("allocations", allocations.stream().map(row -> Arrays.asList(row.getId(), row.getBatchId(), row.getBatchNo(),
                row.getAllocatedQuantity())).toList());
        String localFingerprint = hash(json(local));
        String supplyFingerprint = hash(supplies.stream().map(PlanMrpSupplyWithdrawalDTO.Evidence::getEvidenceFingerprint)
                .collect(Collectors.joining("|")));
        String mesFingerprint = hash("MES_NOT_ISSUED");
        Object mesSnapshot = Map.of("status", "NOT_APPLICABLE");
        if (previewRemote && IssueStatusEnum.ISSUED.getCode().equals(issue.getStatus())) {
            R<MesPlanWithdrawalDTO.Receipt> response = remoteMesTaskService.withdrawPlan(
                    mesCommand(null, issue, true, null));
            require(response != null && response.isOk() && response.getData() != null
                            && "ELIGIBLE".equals(response.getData().getStatus())
                            && StrUtil.isNotBlank(response.getData().getEvidenceFingerprint()),
                    "MES计划撤回资格校验失败：" + (response == null ? "无响应" : response.getMsg()));
            mesFingerprint = response.getData().getEvidenceFingerprint();
            mesSnapshot = response.getData();
        } else if (IssueStatusEnum.ISSUED.getCode().equals(issue.getStatus())) {
            mesFingerprint = hash("MES_DEFERRED_TO_IDEMPOTENT_EXECUTION");
            mesSnapshot = Map.of("status", "DEFERRED");
        }
        String combined = hash(localFingerprint + "|" + supplyFingerprint + "|" + mesFingerprint + "|"
                + quantity(targetQuantity) + "|" + targetDemandDate);
        return new Evidence(demand, localFingerprint, supplyFingerprint, mesFingerprint, combined, supplies,
                json(Map.of("local", local, "supply", supplies, "mes", mesSnapshot,
                        "targetQuantity", targetQuantity, "targetDemandDate", targetDemandDate,
                        "capturedTime", LocalDateTime.now())));
    }

    private MesPlanWithdrawalDTO.Command mesCommand(PlnIssueChangeEntity entity, PlnIssueEntity issue,
                                                     boolean preview, String expectedEvidence) {
        MesPlanWithdrawalDTO.Command command = new MesPlanWithdrawalDTO.Command();
        command.setRequestId(preview ? limit("PIC-PREVIEW-" + issue.getId() + "-" + IdUtil.getSnowflakeNextIdStr(), 64)
                : entity.getExecutionRequestId());
        command.setIssueId(issue.getId());
        command.setIssueNo(issue.getIssueNo());
        command.setExpectedQuantity(issue.getNum());
        command.setFactoryCode(issue.getFactoryCode());
        command.setPreview(preview);
        command.setExpectedEvidenceFingerprint(expectedEvidence);
        command.setReason(preview ? "生产计划受控变更申请/复核前资格校验" : entity.getReason());
        return command;
    }

    private void unlockAps(PlnIssueEntity issue) {
        operationSchedulingMapper.update(null, new LambdaUpdateWrapper<PlnSchedulingOperationEntity>()
                .eq(PlnSchedulingOperationEntity::getIssueId, issue.getId())
                .set(PlnSchedulingOperationEntity::getLockStatus, false)
                .set(PlnSchedulingOperationEntity::getLockTime, null)
                .set(PlnSchedulingOperationEntity::getScheduleStatus, "WITHDRAWN"));
        taskDetailMapper.update(null, new LambdaUpdateWrapper<PlnSchedulingTaskDetailEntity>()
                .eq(PlnSchedulingTaskDetailEntity::getIssueId, issue.getId())
                .set(PlnSchedulingTaskDetailEntity::getLockStatus, false)
                .set(PlnSchedulingTaskDetailEntity::getLockTaskId, null)
                .set(PlnSchedulingTaskDetailEntity::getLockTime, null)
                .set(PlnSchedulingTaskDetailEntity::getOperationScheduleStatus, "WITHDRAWN"));
        List<PlnSchedulingBatchAllocationEntity> allocations = batchAllocationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity>()
                        .eq(PlnSchedulingBatchAllocationEntity::getIssueId, issue.getId()));
        for (PlnSchedulingBatchAllocationEntity allocation : allocations) {
            require(batchAllocationMapper.selectCount(new LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity>()
                    .eq(PlnSchedulingBatchAllocationEntity::getBatchId, allocation.getBatchId())) == 1,
                    "APS批次来源在审批后发生变化，禁止撤回");
            batchMapper.update(null, new LambdaUpdateWrapper<PlnSchedulingBatchEntity>()
                    .eq(PlnSchedulingBatchEntity::getId, allocation.getBatchId())
                    .set(PlnSchedulingBatchEntity::getLockStatus, false)
                    .set(PlnSchedulingBatchEntity::getLockTime, null)
                    .set(PlnSchedulingBatchEntity::getBatchStatus, "WITHDRAWN"));
        }
    }

    @Override
    public PlnIssueChangeEntity detail(Long id) {
        PlnIssueChangeEntity entity = requireChange(id, false);
        requireFactory(entity.getFactoryCode());
        return entity;
    }

    @Override
    public List<PlnIssueChangeOperationEntity> operations(Long id) {
        detail(id);
        return operationMapper.selectList(new LambdaQueryWrapper<PlnIssueChangeOperationEntity>()
                .eq(PlnIssueChangeOperationEntity::getChangeId, id)
                .orderByAsc(PlnIssueChangeOperationEntity::getOperationTime)
                .orderByAsc(PlnIssueChangeOperationEntity::getId));
    }

    @Override
    public Page<PlnIssueChangeEntity> page(SearchPage page, Long issueId, String status) {
        LambdaQueryWrapper<PlnIssueChangeEntity> query = new LambdaQueryWrapper<PlnIssueChangeEntity>()
                .eq(issueId != null, PlnIssueChangeEntity::getIssueId, issueId)
                .eq(StrUtil.isNotBlank(status), PlnIssueChangeEntity::getStatus, status)
                .orderByDesc(PlnIssueChangeEntity::getSubmittedTime).orderByDesc(PlnIssueChangeEntity::getId);
        String factory = FactoryContextHolder.getFactoryCode();
        query.eq(StrUtil.isNotBlank(factory), PlnIssueChangeEntity::getFactoryCode, factory);
        return baseMapper.selectPage(page.toMpPage(), query);
    }

    private List<PlanMatMrpEntity> mrpRows(String demandNo, boolean lock) {
        LambdaQueryWrapper<PlanMatMrpEntity> query = new LambdaQueryWrapper<PlanMatMrpEntity>()
                .eq(PlanMatMrpEntity::getPlanDemandNo, demandNo).orderByAsc(PlanMatMrpEntity::getId);
        if (lock) query.last("FOR UPDATE");
        return mrpMapper.selectList(query);
    }

    private PlnIssueEntity requireIssue(Long id, boolean lock) {
        LambdaQueryWrapper<PlnIssueEntity> query = new LambdaQueryWrapper<PlnIssueEntity>().eq(PlnIssueEntity::getId, id);
        if (lock) query.last("FOR UPDATE");
        PlnIssueEntity entity = issueMapper.selectOne(query);
        require(entity != null, "生产计划不存在");
        return entity;
    }

    private PlnProductDemandEntity requireDemand(Long id, boolean lock) {
        LambdaQueryWrapper<PlnProductDemandEntity> query = new LambdaQueryWrapper<PlnProductDemandEntity>()
                .eq(PlnProductDemandEntity::getId, id);
        if (lock) query.last("FOR UPDATE");
        PlnProductDemandEntity entity = demandMapper.selectOne(query);
        require(entity != null, "产品需求不存在");
        return entity;
    }

    private PlnIssueChangeEntity requireChange(Long id, boolean lock) {
        LambdaQueryWrapper<PlnIssueChangeEntity> query = new LambdaQueryWrapper<PlnIssueChangeEntity>()
                .eq(PlnIssueChangeEntity::getId, id);
        if (lock) query.last("FOR UPDATE");
        PlnIssueChangeEntity entity = baseMapper.selectOne(query);
        require(entity != null, "生产计划变更单不存在");
        return entity;
    }

    private PlnIssueChangeOperationEntity operationByRequest(String requestId) {
        return operationMapper.selectOne(new LambdaQueryWrapper<PlnIssueChangeOperationEntity>()
                .eq(PlnIssueChangeOperationEntity::getRequestId, requestId), false);
    }

    private PlnIssueChangeEntity changeBySubmitRequest(String requestId) {
        return baseMapper.selectOne(new LambdaQueryWrapper<PlnIssueChangeEntity>()
                .eq(PlnIssueChangeEntity::getSubmitRequestId, requestId), false);
    }

    private void audit(PlnIssueChangeEntity entity, String requestId, String requestFingerprint,
                       String operationType, String before, String after, String content, MomUser user) {
        require(operationMapper.insert(new PlnIssueChangeOperationEntity().setChangeId(entity.getId())
                .setRequestId(requestId).setRequestFingerprint(requestFingerprint).setOperationType(operationType)
                .setBeforeStatus(before).setAfterStatus(after).setContent(limit(content, 1000))
                .setOperatorCode(user.getUsername()).setOperatorName(userName(user))
                .setOperationTime(LocalDateTime.now()).setFactoryCode(entity.getFactoryCode())) == 1,
                "生产计划变更操作履历保存失败");
    }

    private void validateTarget(PlnIssueChangeDTO.SubmitCommand command) {
        require(command != null && command.getIssueId() != null && StrUtil.isNotBlank(command.getRequestId())
                        && command.getTargetQuantity() != null && command.getTargetQuantity().signum() > 0
                        && command.getTargetDemandDate() != null && StrUtil.isNotBlank(command.getReason()),
                "变更申请必须提供生产计划、请求号、目标数量、目标交期和原因");
    }

    private boolean anySucceeded(PlnIssueChangeEntity entity) {
        return Stream.of(entity.getSupplyPhaseStatus(), entity.getMesPhaseStatus(), entity.getWmsPhaseStatus(),
                        entity.getApsPhaseStatus(), entity.getPlanPhaseStatus()).filter(Objects::nonNull)
                .anyMatch(value -> Set.of("SUCCEEDED", "NOT_APPLICABLE").contains(value));
    }

    private boolean zero(BigDecimal value) {
        return value == null || value.signum() == 0;
    }

    private String quantity(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new CheckedException("生产计划变更证据序列化失败");
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("生产计划变更证据指纹生成失败");
        }
    }

    private String fingerprint(String value) {
        String result = value == null ? null : value.trim();
        require(result != null && result.length() == 64, "证据指纹必须为64位");
        return result;
    }

    private String text(String value, int max, String label) {
        String result = value == null ? null : value.trim();
        require(StrUtil.isNotBlank(result) && result.length() <= max, label + "不能为空且不能超过" + max + "字");
        return result;
    }

    private String requestId(String value, String label) {
        String result = value == null ? null : value.trim();
        require(result != null && result.length() >= 8 && result.length() <= 64,
                label + "长度必须为8到64位");
        return result;
    }

    private String limit(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private void requireFactory(String factoryCode) {
        String current = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isBlank(current) || Objects.equals(current, factoryCode), "当前工厂无权操作该生产计划变更");
    }

    private MomUser currentUser() {
        MomUser user;
        try {
            user = SecurityUtils.getUser();
        } catch (Exception ex) {
            throw new CheckedException("生产计划受控变更必须由已认证用户发起");
        }
        require(user != null && StrUtil.isNotBlank(user.getUsername()), "生产计划受控变更必须由已认证用户发起");
        return user;
    }

    private String userName(MomUser user) {
        return StrUtil.blankToDefault(user.getName(), user.getUsername());
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    private record Evidence(PlnProductDemandEntity demand, String localFingerprint, String supplyFingerprint,
                            String mesFingerprint, String combinedFingerprint,
                            List<PlanMrpSupplyWithdrawalDTO.Evidence> supplyEvidence, String snapshot) {
    }
}
