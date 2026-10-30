package com.kitex.mom.plan.service.impl;

import com.kitex.mom.plan.service.aps.PlnApsSourceRouting;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.plan.dto.PlanSchedulingAgentDTO;
import com.kitex.mom.plan.dto.PlnApsCaseDTO;
import com.kitex.mom.plan.dto.PlnApsDecisionDTO;
import com.kitex.mom.plan.entity.PlnApsA3ApprovalEntity;
import com.kitex.mom.plan.entity.PlnSchedulingScenarioEntity;
import com.kitex.mom.plan.mapper.PlnApsA3ApprovalMapper;
import com.kitex.mom.plan.mapper.PlnApsCaseMapper;
import com.kitex.mom.plan.service.PlnApsA3ApprovalService;
import com.kitex.mom.plan.service.PlnApsCaseService;
import com.kitex.mom.plan.service.PlnSchedulingAgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * A3 审批账实现。所有身份取自登录上下文，客户端只提交理由和已读取的证据指纹。
 * 同案件操作使用 PostgreSQL 事务级锁串行化，避免双审批或审批与发布竞态。
 */
@Service
@RequiredArgsConstructor
public class PlnApsA3ApprovalServiceImpl extends ServiceImpl<PlnApsA3ApprovalMapper, PlnApsA3ApprovalEntity>
        implements PlnApsA3ApprovalService {

    private final PlnApsCaseService caseService;
    private final PlnSchedulingAgentService schedulingAgentService;
    private final PlnApsCaseMapper caseMapper;

    private static boolean requestId(String value) {
        return StrUtil.isNotBlank(value) && value.trim().length() <= 64;
    }

    private static boolean fingerprint(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static boolean reason(String value) {
        return StrUtil.isNotBlank(value) && value.trim().length() <= 500;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Override
    public PlnApsDecisionDTO.Approval current(Long caseId) {
        PlnApsCaseDTO apsCase = caseService.detail(caseId);
        PlnApsSourceRouting.requireStandard(apsCase);
        return summary(latest(apsCase));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnApsDecisionDTO.Approval submit(Long caseId, PlnApsDecisionDTO.ApprovalSubmitCommand command) {
        require(command != null && requestId(command.getRequestId())
                && fingerprint(command.getExpectedCaseFingerprint())
                && fingerprint(command.getExpectedScenarioFingerprint())
                && fingerprint(command.getExpectedSelectionFingerprint())
                && reason(command.getReason()), "A3送审须填写原因并携带当前案件和A2证据");
        PlnApsCaseDTO apsCase = lockCase(caseId);
        PlnApsA3ApprovalEntity previous = latest(apsCase);
        if (previous != null && Objects.equals(previous.getSubmitRequestId(), command.getRequestId().trim())) {
            require(Objects.equals(previous.getCaseFingerprint(), command.getExpectedCaseFingerprint())
                            && Objects.equals(previous.getScenarioFingerprint(), command.getExpectedScenarioFingerprint())
                            && Objects.equals(previous.getSelectionFingerprint(), command.getExpectedSelectionFingerprint())
                            && Objects.equals(previous.getSubmitReason(), command.getReason().trim()),
                    "同一A3送审请求号已对应不同内容");
            return summary(previous);
        }
        require("SELECTED".equals(apsCase.getCaseStatus()), "仅完成A2选案的案件可以送审");
        require(Objects.equals(apsCase.getContextFingerprint(), command.getExpectedCaseFingerprint()),
                "案件证据已变化，请刷新后送审");
        PlanSchedulingAgentDTO.ScenarioEvidence evidence = evidence(apsCase);
        PlnSchedulingScenarioEntity scenario = evidence.getScenario().getScenario();
        require(Objects.equals(evidence.getScenarioFingerprint(), command.getExpectedScenarioFingerprint())
                        && Objects.equals(scenario.getSelectionFingerprint(), command.getExpectedSelectionFingerprint())
                        && Objects.equals(String.valueOf(scenario.getSelectedTaskId()), apsCase.getSelectedTaskId()),
                "候选或A2选择证据已变化，请刷新后送审");
        require(StrUtil.isNotBlank(scenario.getSelectedBy())
                        && Objects.equals(operator(), scenario.getSelectedBy()),
                "A3送审必须由实际A2选案人发起");
        if (previous != null && ("PENDING".equals(previous.getStatus()) || "APPROVED".equals(previous.getStatus()))) {
            require(!frozenChanged(previous, apsCase, evidence), "同一方案已有待审或已通过记录，请勿重复送审");
            previous.setStatus("INVALIDATED");
            previous.setDecisionReason("案件或候选证据变化后重新送审");
            previous.setUpdateBy(operator());
            previous.setUpdateTime(LocalDateTime.now());
            updateById(previous);
        }
        long id = IdUtil.getSnowflakeNextId();
        String applicant = operator();
        PlnApsA3ApprovalEntity approval = new PlnApsA3ApprovalEntity();
        approval.setId(id);
        approval.setTenantId(TenantContextHolder.getTenantId());
        approval.setFactoryCode(apsCase.getFactoryCode());
        approval.setCaseId(caseId);
        approval.setApprovalNo("APSA3-" + id);
        approval.setStatus("PENDING");
        approval.setScenarioId(Long.valueOf(apsCase.getScenarioId()));
        approval.setSelectedTaskId(Long.valueOf(apsCase.getSelectedTaskId()));
        approval.setCaseFingerprint(apsCase.getContextFingerprint());
        approval.setScenarioFingerprint(evidence.getScenarioFingerprint());
        approval.setSelectionFingerprint(scenario.getSelectionFingerprint());
        approval.setSubmitRequestId(command.getRequestId().trim());
        approval.setPublishRequestId("APS-A3-" + id);
        approval.setApplicantCode(applicant);
        approval.setSubmitReason(command.getReason().trim());
        approval.setSubmittedTime(LocalDateTime.now());
        approval.setCreateBy(applicant);
        approval.setCreateTime(LocalDateTime.now());
        approval.setDelFlag("0");
        require(save(approval), "A3送审记录保存失败");
        return summary(approval);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnApsDecisionDTO.Approval decide(Long caseId, PlnApsDecisionDTO.ApprovalDecisionCommand command) {
        require(command != null && requestId(command.getRequestId()) && StrUtil.isNotBlank(command.getApprovalId())
                        && command.getApproved() != null && reason(command.getReason()),
                "A3审核须指定待审记录、审核决定和原因");
        PlnApsCaseDTO apsCase = lockCase(caseId);
        PlnApsA3ApprovalEntity approval = latest(apsCase);
        require(approval != null && Objects.equals(String.valueOf(approval.getId()), command.getApprovalId()),
                "A3审批记录已变化，请刷新工作台");
        if (Objects.equals(approval.getDecisionRequestId(), command.getRequestId().trim())) {
            require(Objects.equals(approval.getStatus(), Boolean.TRUE.equals(command.getApproved()) ? "APPROVED" : "REJECTED")
                            && Objects.equals(approval.getDecisionReason(), command.getReason().trim()),
                    "同一A3审核请求号已对应不同决定");
            return summary(approval);
        }
        require("PENDING".equals(approval.getStatus()), "只有待审的A3记录可以审核");
        String reviewer = operator();
        require(!Objects.equals(reviewer, approval.getApplicantCode()), "A3审批必须由申请人之外的账号完成");
        require("SELECTED".equals(apsCase.getCaseStatus()), "案件已离开A2选案状态，不能审批");
        PlanSchedulingAgentDTO.ScenarioEvidence evidence = evidence(apsCase);
        require(!frozenChanged(approval, apsCase, evidence), "A3冻结证据已变化，不能审批；请重新送审");
        require(!Objects.equals(reviewer, evidence.getScenario().getScenario().getSelectedBy()),
                "A3审批必须由A2选案人之外的账号完成");
        approval.setStatus(Boolean.TRUE.equals(command.getApproved()) ? "APPROVED" : "REJECTED");
        approval.setDecisionRequestId(command.getRequestId().trim());
        approval.setDecisionReason(command.getReason().trim());
        approval.setApproverCode(reviewer);
        approval.setDecidedTime(LocalDateTime.now());
        approval.setUpdateBy(reviewer);
        approval.setUpdateTime(LocalDateTime.now());
        require(updateById(approval), "A3审核结果保存失败");
        return summary(approval);
    }

    @Override
    public PlnApsA3ApprovalEntity requireApproved(Long caseId, PlnApsDecisionDTO.PublishCommand command,
                                                  boolean replay) {
        PlnApsCaseDTO apsCase = lockCase(caseId);
        PlnApsA3ApprovalEntity approval = latest(apsCase);
        require(approval != null && Objects.equals(String.valueOf(approval.getId()), command.getApprovalId())
                        && Objects.equals(approval.getPublishRequestId(), command.getRequestId())
                        && (replay ? "CONSUMED".equals(approval.getStatus())
                        : "APPROVED".equals(approval.getStatus())),
                "没有与本次发布匹配的有效A3异岗审批");
        require(Objects.equals(operator(), approval.getApproverCode()), "只有实际A3审批人可以正式发布");
        require(Objects.equals(approval.getCaseFingerprint(), command.getExpectedCaseFingerprint())
                        && Objects.equals(approval.getScenarioFingerprint(), command.getExpectedScenarioFingerprint())
                        && Objects.equals(approval.getSelectionFingerprint(), command.getExpectedSelectionFingerprint()),
                "发布请求与A3冻结证据不一致");
        if (!replay) {
            require(!frozenChanged(approval, apsCase, evidence(apsCase)), "A3审批后案件或候选已变化，请重新送审");
        }
        return approval;
    }

    @Override
    public void consume(PlnApsA3ApprovalEntity approval) {
        require("APPROVED".equals(approval.getStatus()), "A3审批已被消费或撤销");
        approval.setStatus("CONSUMED");
        approval.setConsumedTime(LocalDateTime.now());
        approval.setUpdateBy(operator());
        approval.setUpdateTime(LocalDateTime.now());
        require(updateById(approval), "A3审批消费留痕失败");
    }

    private PlnApsCaseDTO lockCase(Long caseId) {
        PlnApsCaseDTO apsCase = caseService.detail(caseId);
        PlnApsSourceRouting.requireStandard(apsCase);
        caseMapper.lock("APS_A3:" + apsCase.getFactoryCode() + ':' + caseId);
        return caseService.detail(caseId);
    }

    private PlnApsA3ApprovalEntity latest(PlnApsCaseDTO apsCase) {
        return baseMapper.selectOne(new LambdaQueryWrapper<PlnApsA3ApprovalEntity>()
                .eq(PlnApsA3ApprovalEntity::getCaseId, Long.valueOf(apsCase.getId()))
                .eq(PlnApsA3ApprovalEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(PlnApsA3ApprovalEntity::getFactoryCode, apsCase.getFactoryCode())
                .eq(PlnApsA3ApprovalEntity::getDelFlag, "0")
                .orderByDesc(PlnApsA3ApprovalEntity::getId).last("LIMIT 1"));
    }

    private PlanSchedulingAgentDTO.ScenarioEvidence evidence(PlnApsCaseDTO apsCase) {
        PlanSchedulingAgentDTO.ScenarioQuery query = new PlanSchedulingAgentDTO.ScenarioQuery();
        query.setFactoryCode(apsCase.getFactoryCode());
        query.setScenarioId(Long.valueOf(apsCase.getScenarioId()));
        return schedulingAgentService.query(query);
    }

    private boolean frozenChanged(PlnApsA3ApprovalEntity approval, PlnApsCaseDTO apsCase,
                                  PlanSchedulingAgentDTO.ScenarioEvidence evidence) {
        PlnSchedulingScenarioEntity scenario = evidence.getScenario().getScenario();
        return !Objects.equals(approval.getCaseFingerprint(), apsCase.getContextFingerprint())
                || !Objects.equals(approval.getScenarioId(), scenario.getId())
                || !Objects.equals(approval.getSelectedTaskId(), scenario.getSelectedTaskId())
                || !Objects.equals(approval.getSelectedTaskId().toString(), apsCase.getSelectedTaskId())
                || !Objects.equals(approval.getScenarioFingerprint(), evidence.getScenarioFingerprint())
                || !Objects.equals(approval.getSelectionFingerprint(), scenario.getSelectionFingerprint());
    }

    private String operator() {
        String username = SecurityUtils.getUser() == null ? null : SecurityUtils.getUser().getUsername();
        require(StrUtil.isNotBlank(username), "A3审批必须使用实名登录账号");
        return username.trim();
    }

    private PlnApsDecisionDTO.Approval summary(PlnApsA3ApprovalEntity row) {
        if (row == null) return null;
        return new PlnApsDecisionDTO.Approval()
                .setId(row.getId().toString()).setApprovalNo(row.getApprovalNo()).setStatus(row.getStatus())
                .setApplicantCode(row.getApplicantCode()).setApproverCode(row.getApproverCode())
                .setSubmitReason(row.getSubmitReason()).setDecisionReason(row.getDecisionReason())
                .setCaseFingerprint(row.getCaseFingerprint()).setScenarioFingerprint(row.getScenarioFingerprint())
                .setSelectionFingerprint(row.getSelectionFingerprint()).setPublishRequestId(row.getPublishRequestId())
                .setSubmittedTime(row.getSubmittedTime()).setDecidedTime(row.getDecidedTime());
    }
}
