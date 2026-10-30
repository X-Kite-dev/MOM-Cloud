package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesReportReverseRequestDTO;
import com.kitex.mom.mes.dto.MesTaskReportReverseVO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.QualityQuantityStatusEnum;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.enums.ReportStatusEnum;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.*;
import com.kitex.mom.mes.util.MesFrozenExecutionGraph;
import com.kitex.mom.mes.util.MesReportReverseNodeEvidence;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 报工冲销的申请、事实冻结、异岗复核和原子执行。
 *
 * <p>当前版本只自动处理未进入QMS数量判定、未形成下游工序或入库事实的报工。
 * 已质量判定、质量隔离、在途或下游已执行的情形故障关闭，不伪造跨域逆向。</p>
 */
@Service
@RequiredArgsConstructor
public class MesReportReverseRequestServiceImpl
        extends ServiceImpl<MesReportReverseRequestMapper, MesReportReverseRequestEntity>
        implements MesReportReverseRequestService {

    private final MesReportReverseOperationMapper operationMapper;
    private final MesTaskReportService reportService;
    private final MesTaskService taskService;
    private final MesTaskArrangeService arrangeService;
    private final MesProduceTaskService produceTaskService;
    private final MesReportReverseScopeService scopeService;
    private final MesInboundAllocationService inboundAllocationService;
    private final MesWarehouseLiveStockService liveStockService;
    private final MesWarehouseInoutService inoutService;
    private final MesReportMaterialConsumeService consumeService;
    private final MesWrapRecordService wrapRecordService;
    private final MesQualityStockProofMapper qualityStockProofMapper;
    private final MesInventoryQualityCaseMapper qualityCaseMapper;
    private final MesWipTransferMapper wipTransferMapper;
    private final MesTaskReportReverseService reverseService;
    private final MesPlanCompletionReopenEventService completionReopenEventService;
    private final MesActualFactoryAccessService factoryAccess;
    private final MesExecutionTaskLock taskLock;
    private final MesIdentifierBindingGroupService bindingGroups;
    private final MesIdentifierBindingService identifierBindings;
    private final MesIdentifierBindingInverseCoordinator bindingInverse;

    @Override
    @Transactional(readOnly = true)
    public IPage<MesReportReverseRequestEntity> pageRequests(Page<MesReportReverseRequestEntity> page,
                                                             MesReportReverseRequestEntity query) {
        String factory = factoryAccess.require("read");
        require(page.getCurrent() > 0 && page.getSize() > 0 && page.getSize() <= 100, "页码或分页大小无效");
        MesReportReverseRequestEntity filter = query == null ? new MesReportReverseRequestEntity() : query;
        return page(page, new LambdaQueryWrapper<MesReportReverseRequestEntity>()
                .eq(MesReportReverseRequestEntity::getFactoryCode, factory)
                .eq(StrUtil.isNotBlank(filter.getStatus()), MesReportReverseRequestEntity::getStatus,
                        StrUtil.trim(filter.getStatus()))
                .like(StrUtil.isNotBlank(filter.getTaskCode()), MesReportReverseRequestEntity::getTaskCode,
                        StrUtil.trim(filter.getTaskCode()))
                .like(StrUtil.isNotBlank(filter.getArrangeCode()), MesReportReverseRequestEntity::getArrangeCode,
                        StrUtil.trim(filter.getArrangeCode()))
                .like(StrUtil.isNotBlank(filter.getApplicantName()), MesReportReverseRequestEntity::getApplicantName,
                        StrUtil.trim(filter.getApplicantName()))
                .orderByDesc(MesReportReverseRequestEntity::getSubmittedTime)
                .orderByDesc(MesReportReverseRequestEntity::getId));
    }

    @Override
    @Transactional(readOnly = true)
    public MesReportReverseRequestDTO.Evidence preview(Long reportId, String correctionMode) {
        String factory = factoryAccess.require("read");
        CorrectionEvidence selected = correctionEvidence(reportId, factory, false, correctionMode);
        MesReportReverseRequestEntity active = activeRequest(selected.reportIds(), false);
        List<String> blockers = new ArrayList<>(selected.blockers());
        if (active != null) blockers.add("影响链中已有待审冲销申请：" + active.getRequestNo());
        return toEvidence(selected, active, blockers);
    }

    @Override
    @Transactional(readOnly = true)
    public MesReportReverseRequestDTO.Detail detail(Long requestId) {
        String factory = factoryAccess.require("read");
        MesReportReverseRequestEntity request = request(requestId, factory, false);
        String originalVersion = MesReportReverseNodeEvidence.originalVersion(request);
        MesReportReverseRequestDTO.Evidence currentEvidence;
        try {
            CorrectionEvidence selected = correctionEvidence(request.getReportId(), factory, false,
                    StrUtil.blankToDefault(request.getCorrectionMode(), "SINGLE"), originalVersion);
            currentEvidence = toEvidence(selected, activeRequest(selected.reportIds(), false), selected.blockers());
        } catch (CheckedException exception) {
            // 当前来源已不满足取证条件时，仍允许查看原申请并异岗驳回；绝不能显示可执行。
            currentEvidence = new MesReportReverseRequestDTO.Evidence().setEligible(false)
                    .setChainReports(List.of()).setBlockers(List.of(exception.getMessage()));
        }
        List<MesReportReverseOperationEntity> operations = operationMapper.selectList(
                new LambdaQueryWrapper<MesReportReverseOperationEntity>()
                        .eq(MesReportReverseOperationEntity::getReverseRequestId, request.getId())
                        .orderByAsc(MesReportReverseOperationEntity::getId));
        List<MesReportReverseScopeEntity> scopes = scopes(request.getId());
        return new MesReportReverseRequestDTO.Detail().setRequest(request)
                .setEvidenceVersion(originalVersion)
                .setFrozenNodeScopes(MesReportReverseNodeEvidence.frozenScopes(request))
                .setCurrentEvidence(currentEvidence).setOperations(operations).setScopes(scopes);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesReportReverseRequestEntity submit(MesReportReverseRequestDTO.SubmitCommand command) {
        String factory = factoryAccess.require("write");
        validateSubmit(command);
        MomUser user = actor();
        String commandId = command.getRequestId().trim();
        String requestedMode = normalizeCorrectionMode(command.getCorrectionMode(), true);
        String commandFingerprint = hash(Arrays.asList(command.getReportId(),
                requestedMode, command.getExpectedEvidenceFingerprint().trim(),
                command.getCorrectionQuantity(), Boolean.TRUE.equals(command.getCreateReReportPermit()),
                command.getReason().trim(), user.getUsername()));
        baseMapper.lockKey("COMMAND:" + commandId);
        MesReportReverseRequestEntity replay = getOne(new LambdaQueryWrapper<MesReportReverseRequestEntity>()
                .eq(MesReportReverseRequestEntity::getSubmitRequestId, commandId), false);
        if (replay != null) {
            require(Objects.equals(replay.getSubmitFingerprint(), commandFingerprint),
                    "冲销提交请求号已被不同命令使用");
            return replay;
        }
        MesTaskReportEntity ownership = taskLock.report(command.getReportId());
        CorrectionEvidence selected = correctionEvidence(command.getReportId(), factory, true, requestedMode);
        taskLock.assertTaskCode(ownership.getTaskCode(), selected.anchor().report().getTaskCode());
        require(selected.blockers().isEmpty(), String.join("；", selected.blockers()));
        require(Objects.equals(selected.fingerprint(), command.getExpectedEvidenceFingerprint().trim()),
                "报工、库存或投料事实已变化，请刷新预览后重新申请");
        require(activeRequest(selected.reportIds(), true) == null, "影响链中已有报工进入待审冲销申请");

        MesTaskReportEntity report = selected.anchor().report();
        BigDecimal effectiveBefore = effectiveQuantity(report);
        BigDecimal correctionQuantity = "PARTIAL_QUANTITY".equals(selected.mode())
                ? command.getCorrectionQuantity() : effectiveBefore;
        if ("PARTIAL_QUANTITY".equals(selected.mode())) {
            require(correctionQuantity != null && correctionQuantity.signum() > 0
                            && correctionQuantity.compareTo(effectiveBefore) < 0,
                    "部分纠正数量必须大于0且小于当前有效报工数量");
        } else {
            require(command.getCorrectionQuantity() == null,
                    "完整冲销和同标识链式撤回不能混入部分纠正数量");
            require(!Boolean.TRUE.equals(command.getCreateReReportPermit()),
                    "完整冲销不生成聚合数量重报许可");
        }
        LocalDateTime now = LocalDateTime.now();
        MesReportReverseRequestEntity request = new MesReportReverseRequestEntity()
                .setRequestNo(requestNo(commandId, now)).setSubmitRequestId(commandId)
                .setSubmitFingerprint(commandFingerprint).setReportId(report.getId())
                .setReportRequestId(report.getRequestId()).setTaskCode(report.getTaskCode())
                .setProduceTaskCode(report.getProduceTaskCode()).setArrangeCode(report.getArrangeCode())
                .setReportNum(report.getReportNum()).setReportMode(report.getReportMode())
                .setEffectiveQuantityBefore(effectiveBefore).setCorrectionQuantity(correctionQuantity)
                .setEffectiveQuantityAfter(effectiveBefore.subtract(correctionQuantity))
                .setCorrectionMode(selected.mode()).setChainReportCount(selected.steps().size())
                .setStatus("SUBMITTED").setEvidenceFingerprint(selected.fingerprint())
                .setEvidenceSnapshot(selected.json()).setReason(command.getReason().trim())
                .setCreateReReportPermit(Boolean.TRUE.equals(command.getCreateReReportPermit()))
                .setReReportAllowedQuantity(Boolean.TRUE.equals(command.getCreateReReportPermit())
                        ? correctionQuantity : null)
                .setReReportUsedQuantity(Boolean.TRUE.equals(command.getCreateReReportPermit())
                        ? BigDecimal.ZERO : null)
                .setReReportPermitStatus(Boolean.TRUE.equals(command.getCreateReReportPermit())
                        ? "PENDING_APPROVAL" : null)
                .setApplicantId(user.getId()).setApplicantCode(user.getUsername())
                .setApplicantName(name(user)).setSubmittedTime(now).setFactoryCode(factory);
        require(save(request), "报工冲销申请保存失败");
        saveScopes(request, selected);
        saveOperation(request, commandId, commandFingerprint, "SUBMIT", null, "SUBMITTED",
                command.getReason().trim(), user, now);
        return request;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @PreAuthorize("@pms.hasPermission('mesTaskReportReverse:review')")
    public MesReportReverseRequestEntity review(MesReportReverseRequestDTO.ReviewCommand command) {
        String factory = factoryAccess.require("write");
        validateReview(command);
        MomUser user = actor();
        String commandId = command.getOperationRequestId().trim();
        String commandFingerprint = hash(Arrays.asList(command.getRequestId(), command.getApproved(),
                command.getExpectedEvidenceFingerprint().trim(), command.getReason().trim(), user.getUsername()));
        baseMapper.lockKey("COMMAND:" + commandId);
        MesReportReverseOperationEntity replay = operationMapper.selectOne(
                new LambdaQueryWrapper<MesReportReverseOperationEntity>()
                        .eq(MesReportReverseOperationEntity::getRequestId, commandId), false);
        if (replay != null) {
            require(Objects.equals(replay.getRequestFingerprint(), commandFingerprint),
                    "冲销审批请求号已被不同命令使用");
            return getById(replay.getReverseRequestId());
        }
        MesReportReverseRequestEntity owner = request(command.getRequestId(), factory, false);
        bindingGroups.lockDispatchTask(owner.getTaskCode());
        MesReportReverseRequestEntity request = request(command.getRequestId(), factory, true);
        taskLock.assertTaskCode(owner.getTaskCode(), request.getTaskCode());
        require("SUBMITTED".equals(request.getStatus()), "只有待审冲销申请可复核");
        require(!Objects.equals(request.getApplicantId(), user.getId())
                        && !Objects.equals(request.getApplicantCode(), user.getUsername()),
                "冲销申请人不能审批自己的申请");
        require(Objects.equals(request.getEvidenceFingerprint(), command.getExpectedEvidenceFingerprint().trim()),
                "审批依据与申请冻结事实不一致");

        LocalDateTime now = LocalDateTime.now();
        if (!Boolean.TRUE.equals(command.getApproved())) {
            fillReview(request, false, command, commandFingerprint, user, now);
            request.setStatus("REJECTED").setHandledTime(now).setResultMessage("冲销申请已驳回");
            require(updateById(request), "冲销驳回保存失败");
            deactivateScopes(request.getId());
            saveOperation(request, commandId, commandFingerprint, "REJECT", "SUBMITTED", "REJECTED",
                    command.getReason().trim(), user, now);
            return request;
        }

        CorrectionEvidence current = correctionEvidence(request.getReportId(), factory, true,
                StrUtil.blankToDefault(request.getCorrectionMode(), "SINGLE"),
                MesReportReverseNodeEvidence.originalVersion(request));
        if (!current.blockers().isEmpty() || !Objects.equals(current.fingerprint(), request.getEvidenceFingerprint())
                || !scopeMatches(request, current)) {
            fillReview(request, false, command, commandFingerprint, user, now);
            request.setStatus("STALE").setHandledTime(now)
                    .setResultMessage("批准前事实已变化：" + (current.blockers().isEmpty()
                            ? "冻结范围或指纹不一致" : String.join("；", current.blockers())));
            require(updateById(request), "冲销失效状态保存失败");
            deactivateScopes(request.getId());
            saveOperation(request, commandId, commandFingerprint, "MARK_STALE", "SUBMITTED", "STALE",
                    request.getResultMessage(), user, now);
            return request;
        }

        Map<Long, MesReportReverseScopeEntity> frozenScopes = scopes(request.getId()).stream()
                .collect(java.util.stream.Collectors.toMap(MesReportReverseScopeEntity::getReportId,
                        item -> item, (left, right) -> left, LinkedHashMap::new));
        MesTaskEntity taskBefore = taskService.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getCode, request.getTaskCode())
                .eq(MesTaskEntity::getFactoryCode, factory).last("FOR UPDATE"), false);
        require(taskBefore != null, "冲销影响的生产工单不存在");
        List<Map<String, Object>> reversedResults = new ArrayList<>();
        if ("PARTIAL_QUANTITY".equals(request.getCorrectionMode())) {
            ChainStep step = current.steps().get(0);
            String childRequestId = Optional.ofNullable(frozenScopes.get(step.report().getId()))
                    .map(MesReportReverseScopeEntity::getChildReverseRequestId).orElse(commandId);
            MesTaskReportReverseVO reverseCommand = new MesTaskReportReverseVO();
            reverseCommand.setReportId(step.report().getId());
            reverseCommand.setRequestId(childRequestId);
            reverseCommand.setReason(command.getReason().trim());
            reverseCommand.setCorrectionQuantity(request.getCorrectionQuantity());
            reverseService.reversePartial(reverseCommand, request.getId(), request.getEvidenceFingerprint());
            MesTaskReportEntity corrected = reportService.getById(step.report().getId());
            require(corrected != null && ReportStatusEnum.EFFECTIVE.name().equals(corrected.getStatus())
                            && corrected.getEffectiveReportNum() != null
                            && corrected.getEffectiveReportNum().compareTo(request.getEffectiveQuantityAfter()) == 0,
                    "部分纠正未形成可验证的有效数量结果");
            reversedResults.add(Map.of("reportId", corrected.getId(), "arrangeCode", corrected.getArrangeCode(),
                    "correctionRequestId", childRequestId, "correctionQuantity", request.getCorrectionQuantity(),
                    "effectiveQuantityAfter", corrected.getEffectiveReportNum()));
        } else {
            MesReportReverseExecutionAuthority.runApproved(request, commandId, commandFingerprint, frozenScopes, () -> {
                for (ChainStep step : current.steps()) {
                    String childRequestId = Optional.ofNullable(frozenScopes.get(step.report().getId()))
                            .map(MesReportReverseScopeEntity::getChildReverseRequestId).orElse(commandId);
                    MesTaskReportReverseVO reverseCommand = new MesTaskReportReverseVO();
                    reverseCommand.setReportId(step.report().getId());
                    reverseCommand.setRequestId(childRequestId);
                    reverseCommand.setReason(command.getReason().trim());
                    reverseCommand.setReverseRequestBusinessId(request.getId());
                    reverseCommand.setEvidenceFingerprint(request.getEvidenceFingerprint());
                    reverseService.reverse(reverseCommand);
                    MesTaskReportEntity reversed = reportService.getById(step.report().getId());
                    require(reversed != null && ReportStatusEnum.REVERSED.name().equals(reversed.getStatus())
                                    && Objects.equals(reversed.getReverseRequestId(), childRequestId),
                            "反向事务未形成可验证的逐报工冲销结果，reportId=" + step.report().getId());
                    reversedResults.add(Map.of("reportId", reversed.getId(), "arrangeCode", reversed.getArrangeCode(),
                            "reverseRequestId", childRequestId,
                            "reverseTime", Objects.toString(reversed.getReverseTime(), "")));
                }
            });
        }
        MesTaskEntity taskAfter = taskService.getById(taskBefore.getId());
        require(taskAfter != null, "冲销后的生产工单事实不可读取");
        MesPlanCompletionReopenOutboxEntity completionEvent = completionReopenEventService.enqueue(
                taskBefore, taskAfter, request.getId());
        fillReview(request, true, command, commandFingerprint, user, now);
        Map<String, Object> resultSnapshot = new LinkedHashMap<>();
        resultSnapshot.put("correctionMode", current.mode());
        resultSnapshot.put("reportCount", current.steps().size());
        resultSnapshot.put("reports", reversedResults);
        if (completionEvent != null) {
            resultSnapshot.put("planCompletionReopenEventId", completionEvent.getEventId());
            resultSnapshot.put("planCompletionReopenOutboxId", completionEvent.getId());
        }
        if (Boolean.TRUE.equals(request.getCreateReReportPermit())) {
            request.setReReportPermitNo("RRP-" + request.getRequestNo())
                    .setReReportPermitStatus("ACTIVE").setReReportExpireTime(now.plusDays(7));
            resultSnapshot.put("reReportPermitNo", request.getReReportPermitNo());
            resultSnapshot.put("reReportPermitExpireTime", request.getReReportExpireTime().toString());
        }
        request.setStatus("APPROVED").setHandledTime(now)
                .setResultMessage("PARTIAL_QUANTITY".equals(request.getCorrectionMode())
                        ? "聚合报工部分数量纠正已完成，剩余有效数量" + request.getEffectiveQuantityAfter()
                        : current.steps().size() == 1 ? "报工反向事务已完成"
                        : "同标识下游链已按下游到上游原子撤回，共" + current.steps().size() + "笔")
                .setResultSnapshot(JSONUtil.toJsonStr(resultSnapshot));
        require(updateById(request), "冲销批准回执保存失败");
        deactivateScopes(request.getId());
        saveOperation(request, commandId, commandFingerprint, "APPROVE", "SUBMITTED", "APPROVED",
                command.getReason().trim(), user, now);
        return request;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesReportReverseRequestEntity withdraw(MesReportReverseRequestDTO.WithdrawCommand command) {
        String factory = factoryAccess.require("write");
        require(command != null && command.getRequestId() != null && validRequestId(command.getOperationRequestId())
                && validReason(command.getReason()), "撤回必须提供申请、永久请求号和1至500字原因");
        MomUser user = actor();
        String commandId = command.getOperationRequestId().trim();
        String commandFingerprint = hash(Arrays.asList(command.getRequestId(), command.getReason().trim(), user.getUsername()));
        baseMapper.lockKey("COMMAND:" + commandId);
        MesReportReverseOperationEntity replay = operationMapper.selectOne(
                new LambdaQueryWrapper<MesReportReverseOperationEntity>()
                        .eq(MesReportReverseOperationEntity::getRequestId, commandId), false);
        if (replay != null) {
            require(Objects.equals(replay.getRequestFingerprint(), commandFingerprint),
                    "冲销撤回请求号已被不同命令使用");
            return getById(replay.getReverseRequestId());
        }
        MesReportReverseRequestEntity owner = request(command.getRequestId(), factory, false);
        bindingGroups.lockDispatchTask(owner.getTaskCode());
        MesReportReverseRequestEntity request = request(command.getRequestId(), factory, true);
        taskLock.assertTaskCode(owner.getTaskCode(), request.getTaskCode());
        require("SUBMITTED".equals(request.getStatus()), "只有待审冲销申请可撤回");
        require(Objects.equals(request.getApplicantId(), user.getId())
                        && Objects.equals(request.getApplicantCode(), user.getUsername()),
                "只有原申请人可撤回冲销申请");
        LocalDateTime now = LocalDateTime.now();
        request.setStatus("WITHDRAWN").setHandledTime(now).setResultMessage("申请人已撤回冲销申请");
        require(updateById(request), "冲销申请撤回失败");
        deactivateScopes(request.getId());
        saveOperation(request, commandId, commandFingerprint, "WITHDRAW", "SUBMITTED", "WITHDRAWN",
                command.getReason().trim(), user, now);
        return request;
    }

    private EvidenceSnapshot evidence(Long reportId, String factory, boolean lock) {
        require(reportId != null, "请选择原报工记录");
        Long tenantId = TenantContextHolder.getTenantId();
        if (lock) {
            MesTaskReportEntity observed = reportService.getOne(new LambdaQueryWrapper<MesTaskReportEntity>()
                    .eq(MesTaskReportEntity::getId, reportId).eq(MesTaskReportEntity::getTenantId, tenantId)
                    .eq(MesTaskReportEntity::getFactoryCode, factory), false);
            require(observed != null, "原取证报告不属于当前实际租户工厂");
            bindingInverse.lockForReport(observed);
        }
        MesTaskReportEntity report = reportService.getOne(new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getId, reportId).eq(MesTaskReportEntity::getTenantId, tenantId)
                .eq(MesTaskReportEntity::getFactoryCode, factory).last(lock ? "FOR UPDATE" : ""), false);
        require(report != null, "当前租户和工厂下不存在该报工记录");
        MesIdentifierBindingInverseCoordinator.Evidence dynamic = bindingInverse.evidence(report, lock);
        if (dynamic == null) identifierBindings.assertLegacyCorrectionAllowed(report);
        MesTaskEntity task = taskService.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getCode, report.getTaskCode()).eq(MesTaskEntity::getTenantId, tenantId)
                .eq(MesTaskEntity::getFactoryCode, factory).last(lock ? "FOR UPDATE" : ""), false);
        require(task != null, "原报工对应的生产工单不存在");
        List<MesTaskInWmsReceiptDetailEntity> inboundDetails =
                inboundAllocationService.activeByTask(report.getTaskCode(), factory, lock);
        List<MesWarehouseLiveStockEntity> stocks = liveStockService.list(
                new LambdaQueryWrapper<MesWarehouseLiveStockEntity>()
                        .eq(MesWarehouseLiveStockEntity::getSourceReportId, reportId)
                        .eq(MesWarehouseLiveStockEntity::getFactoryCode, factory)
                        .orderByAsc(MesWarehouseLiveStockEntity::getId)
                        .last(lock ? "LIMIT 1001 FOR UPDATE" : "LIMIT 1001"));
        List<MesWarehouseInoutEntity> flows = inoutService.list(new LambdaQueryWrapper<MesWarehouseInoutEntity>()
                .eq(MesWarehouseInoutEntity::getBillId, reportId)
                .eq(MesWarehouseInoutEntity::getFactoryCode, factory)
                .orderByAsc(MesWarehouseInoutEntity::getId)
                .last(lock ? "LIMIT 2001 FOR UPDATE" : "LIMIT 2001"));
        List<MesReportMaterialConsumeEntity> consumes = consumeService.list(
                new LambdaQueryWrapper<MesReportMaterialConsumeEntity>()
                        .eq(MesReportMaterialConsumeEntity::getReportId, reportId)
                        .eq(MesReportMaterialConsumeEntity::getFactoryCode, factory)
                        .orderByAsc(MesReportMaterialConsumeEntity::getId)
                        .last(lock ? "LIMIT 1001 FOR UPDATE" : "LIMIT 1001"));
        List<MesWrapRecordEntity> wraps = wrapRecordService.list(new LambdaQueryWrapper<MesWrapRecordEntity>()
                .eq(MesWrapRecordEntity::getReportId, reportId)
                .orderByAsc(MesWrapRecordEntity::getId)
                .last(lock ? "LIMIT 1001 FOR UPDATE" : "LIMIT 1001"));
        List<String> blockers = new ArrayList<>();
        if (dynamic != null) blockers.addAll(dynamic.blockers());
        if (stocks.size() > 1000 || consumes.size() > 1000 || wraps.size() > 1000 || flows.size() > 2000) {
            blockers.add("报工关联事实超过安全取证上限，禁止截断冲销");
        }
        BigDecimal trackedInbound = inboundDetails.stream()
                .map(MesTaskInWmsReceiptDetailEntity::getAllocatedQuantity)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal taskInbound = Objects.requireNonNullElse(task.getInStockNum(), BigDecimal.ZERO);
        if (trackedInbound.compareTo(taskInbound) != 0) {
            blockers.add("工单已有成品入库量但缺少等额报工来源明细，须先治理历史归属");
        }
        BigDecimal reportInbound = inboundDetails.stream()
                .filter(item -> Objects.equals(item.getReportId(), reportId))
                .map(MesTaskInWmsReceiptDetailEntity::getAllocatedQuantity)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (reportInbound.signum() > 0) {
            blockers.add("该报工已有成品入库分配数量" + reportInbound.stripTrailingZeros().toPlainString()
                    + "，须先完成WMS成品入库红冲");
        }
        if (!ReportStatusEnum.EFFECTIVE.name().equals(report.getStatus())) {
            blockers.add("只有新版有效报工可申请冲销，当前状态=" + report.getStatus());
        }
        if (MesIdentifierRouteReturnCoordinator.ROUTE_RETURN.equals(report.getExecutionKind())) {
            blockers.add("该记录属于已批准的逐标识路线版本，不能脱离整段路线单独冲销；需重新申请下一路线版本复核执行");
        }
        if (!QualityQuantityStatusEnum.NOT_REQUIRED.name().equals(report.getQualityQuantityStatus())) {
            blockers.add("该报工已进入质量数量判定，须先完成QMS判定逆向，不能直接冲销");
        }
        if (qualityStockProofMapper.selectCount(new LambdaQueryWrapper<MesQualityStockProofEntity>()
                .eq(MesQualityStockProofEntity::getSourceReportId, reportId)) > 0) {
            blockers.add("该报工已形成质量库存变更证据，须走质量逆向流程");
        }
        if (qualityCaseMapper.selectCount(new LambdaQueryWrapper<MesInventoryQualityCaseEntity>()
                .eq(MesInventoryQualityCaseEntity::getSourceReportId, reportId)) > 0
                || stocks.stream().anyMatch(item -> item.getQualityCaseId() != null)) {
            blockers.add("该报工存在质量隔离或处置案件，禁止直接冲销");
        }
        if (wipTransferMapper.selectCount(new LambdaQueryWrapper<MesWipTransferEntity>()
                .eq(MesWipTransferEntity::getSourceReportId, reportId)
                .and(w -> w.ne(MesWipTransferEntity::getStatus, "CLOSED")
                        .or().gt(MesWipTransferEntity::getLostQuantity, BigDecimal.ZERO))) > 0) {
            blockers.add("该报工仍有在途或已核销丢失的WIP，禁止使来源失效");
        }

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("version", "MES_REPORT_REVERSE_V2");
        facts.put("tenantId", report.getTenantId());
        facts.put("factoryCode", report.getFactoryCode());
        facts.put("report", Arrays.asList(report.getId(), report.getRequestId(), report.getStatus(),
                report.getTaskCode(), report.getProduceTaskCode(), report.getArrangeCode(), report.getReportNum(),
                report.getEffectiveReportNum(), report.getCorrectedReportNum(), report.getLastCorrectionTime(),
                report.getReportMode(), report.getTransferCode(), report.getQualityQuantityStatus(),
                report.getQualityReleasedNum(), report.getQualityPendingNum(), report.getQualityRejectedNum()));
        facts.put("taskInbound", Arrays.asList(task.getId(), task.getCode(), task.getInStockNum(),
                task.getUnInStockNum(), trackedInbound));
        facts.put("inboundAllocations", inboundDetails.stream().limit(10000)
                .filter(item -> Objects.equals(item.getReportId(), reportId))
                .map(item -> Arrays.asList(item.getId(), item.getReceiptId(), item.getAllocatedQuantity(),
                        item.getSourceFingerprint())).toList());
        facts.put("stocks", stocks.stream().limit(1000).map(item -> Arrays.asList(item.getId(), item.getNum(),
                item.getWarehouseCode(), item.getSiteCode(), item.getContainerCode(), item.getMatBatch(),
                item.getMatBarcode(), item.getStatus(), item.getInspectionStatus(), item.getQualityCaseId())).toList());
        facts.put("flows", flows.stream().limit(2000).map(item -> Arrays.asList(item.getId(), item.getSourceStockId(),
                item.getOperateType(), item.getBizType(), item.getOperateNum(), item.getResourceNo())).toList());
        facts.put("consumes", consumes.stream().limit(1000).map(item -> Arrays.asList(item.getId(), item.getFeedId(),
                item.getConsumeNum(), item.getStatus(), item.getReverseRequestId())).toList());
        facts.put("wraps", wraps.stream().limit(1000).map(item -> Arrays.asList(item.getId(), item.getWrapCode(),
                item.getQuantity(), item.getAvailableQuantity(), item.getPackingStatus(),
                item.getOperationRequestId())).toList());
        // 仅真实动态分支扩展取证。旧NONE JSON和摘要字节不变，不能追加空字段使待审申请失效。
        if (dynamic != null) facts.put("identifierBindingReverse", dynamic.facts());
        String json = JSONUtil.toJsonStr(facts);
        return new EvidenceSnapshot(report, blockers, hash(json), json, dynamic);
    }

    /**
     * 形成单笔或同标识下游链的完整冻结证据。
     *
     * <p>锁定模式下会先通过原报工取证锁住生产工单，再读取同标识下游报工。正常报工同样锁生产
     * 工单，因此批准事务不会与新下游报工穿插，避免刚冻结完又产生新事实。</p>
     */
    private CorrectionEvidence correctionEvidence(Long reportId, String factory, boolean lock,
                                                  String requestedMode) {
        return correctionEvidence(reportId, factory, lock, requestedMode, null);
    }

    /**
     * 已存申请按原协议重采；首次预览/提交没有客户端可控的降级版本。
     */
    private CorrectionEvidence correctionEvidence(Long reportId, String factory, boolean lock,
                                                  String requestedMode, String originalVersion) {
        EvidenceSnapshot anchor = evidence(reportId, factory, lock);
        ChainPlan plan = chainPlan(anchor.report(), factory, originalVersion);
        String normalized = normalizeCorrectionMode(requestedMode, true);
        String mode = normalized == null
                ? (plan.downstream().isEmpty() ? "SINGLE" : "IDENTIFIER_CHAIN") : normalized;
        List<String> blockers = new ArrayList<>();
        List<ChainCandidate> selectedCandidates;
        if ("IDENTIFIER_CHAIN".equals(mode)) {
            selectedCandidates = plan.all();
            if (!plan.supported()) {
                blockers.add("当前报工模式不能证明逐标识下游归属，禁止自动链式撤回；请先建立批次/SN谱系或拆分差异范围");
            }
            if (plan.downstream().isEmpty()) {
                blockers.add("当前标识没有有效下游报工，无需使用链式撤回");
            }
        } else {
            selectedCandidates = List.of(plan.anchor());
            if (!plan.downstream().isEmpty()) {
                blockers.add("该生产标识已有" + plan.downstream().size()
                        + "笔有效下游报工，单笔纠正会破坏工序链；须先纠正真实下游范围");
            }
            if ("PARTIAL_QUANTITY".equals(mode)) {
                appendPartialCorrectionBlockers(anchor.report(), plan.anchor().arrange(), blockers, lock);
            }
        }
        blockers.addAll(plan.blockers());
        List<ChainStep> steps = new ArrayList<>();
        for (ChainCandidate candidate : selectedCandidates) {
            EvidenceSnapshot snapshot = Objects.equals(candidate.report().getId(), anchor.report().getId())
                    ? anchor : evidence(candidate.report().getId(), factory, lock);
            blockers.addAll(snapshot.blockers().stream()
                    .map(message -> "工序[" + candidate.arrange().getRouteName() + "]：" + message).toList());
            steps.add(new ChainStep(snapshot.report(), candidate.arrange(), candidate.depth(), snapshot));
        }
        Set<Long> coveredReports = steps.stream().map(step -> step.report().getId())
                .collect(java.util.stream.Collectors.toSet());
        for (ChainStep step : steps) {
            if (step.evidence().dynamic() != null) {
                Set<Long> outside = new TreeSet<>(step.evidence().dynamic().dependencies());
                outside.removeAll(coveredReports);
                if (!outside.isEmpty()) blockers.add("原动态节点有未纳入申请的真实后序/使用/释放事实：" + outside);
            }
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        boolean bindingChain = steps.stream().anyMatch(step -> step.evidence().dynamic() != null);
        boolean nativeBindingChain = steps.stream().anyMatch(step -> MesIdentifierBindingInverseCoordinator.isNativeBinding(step.report()));
        // 旧动态永久合同继续V3重采；新节点动态链另V5，逐步补偿仍保留原完整事实和V1正文。
        if (bindingChain && !nativeBindingChain && plan.nodeEvidence() && originalVersion == null) {
            return correctionEvidence(reportId, factory, lock, requestedMode,
                    MesReportReverseNodeEvidence.BINDING_VERSION);
        }
        require(!nativeBindingChain || plan.nodeEvidence(), "原生动态报告不能降为历史主工序撤回合同");
        require(!MesReportReverseNodeEvidence.BINDING_NODE_VERSION.equals(originalVersion) || nativeBindingChain,
                "原生动态撤回原申请缺少原生动态事实");
        require(!bindingChain || !plan.nodeEvidence() || nativeBindingChain, "普通节点撤回不能混用动态绑定批准合同");
        facts.put("version", nativeBindingChain ? MesReportReverseNodeEvidence.BINDING_NODE_VERSION
                : plan.nodeEvidence() ? MesReportReverseNodeEvidence.NODE_VERSION
                : bindingChain ? MesReportReverseNodeEvidence.BINDING_VERSION
                : MesReportReverseNodeEvidence.LEGACY_VERSION);
        facts.put("mode", mode);
        facts.put("anchorReportId", anchor.report().getId());
        facts.put("identifier", Objects.toString(anchor.report().getTransferCode(), ""));
        facts.put("reports", steps.stream().map(step -> Arrays.asList(step.report().getId(),
                step.report().getRequestId(), step.report().getArrangeCode(), step.arrange().getRouteCode(),
                step.arrange().getRouteOrder(), step.report().getReportNum(),
                step.report().getEffectiveReportNum(), step.report().getCorrectedReportNum(),
                step.evidence().fingerprint())).toList());
        if (plan.nodeEvidence()) {
            facts.put("routeExecutionSchemaVersion", MesFrozenExecutionGraph.NODE_EXECUTION_V1);
            facts.put("executionNodes", plan.executionNodes());
            facts.put("nodeScopes", steps.stream().map(step ->
                    MesReportReverseNodeEvidence.nodeScope(step.report(), step.arrange())).toList());
        }
        if (bindingChain) {
            // 动态链永久保存每步原始完整事实，不只存摘要。执行中先撤后序会改变当前实例/库存，
            // 补偿账必须能区分批准时事实和本步实际事实；普通NONE维持原JSON字节。
            facts.put("bindingScopeSnapshots", steps.stream().map(step -> {
                Map<String, Object> scopeSnapshot = new LinkedHashMap<>();
                scopeSnapshot.put("reportId", step.report().getId().toString());
                scopeSnapshot.put("evidenceSnapshot", step.evidence().json());
                return scopeSnapshot;
            }).toList());
        }
        String json = JSONUtil.toJsonStr(facts);
        require(!(bindingChain || plan.nodeEvidence()) || json.getBytes(StandardCharsets.UTF_8).length <= 16 * 1024 * 1024,
                "完整批准事实超过16MiB核对上限，禁止截断；请治理历史并人工核定范围");
        return new CorrectionEvidence(anchor, mode, List.copyOf(steps), List.copyOf(blockers), hash(json), json);
    }

    private void appendPartialCorrectionBlockers(MesTaskReportEntity report, MesTaskArrangeEntity arrange,
                                                 List<String> blockers, boolean lock) {
        if (report.getEffectiveReportNum() == null || report.getCorrectedReportNum() == null) {
            blockers.add("历史报工未冻结有效数量和累计纠正谱系，禁止按输入数量猜测部分冲减");
            return;
        }
        if (report.getEffectiveReportNum().signum() <= 0) {
            blockers.add("当前没有可纠正的有效报工数量");
        }
        ReportModeEnum mode;
        try {
            mode = ReportModeEnum.fromCodes(report.getReportMode(), arrange.getRouteType());
        } catch (IllegalArgumentException exception) {
            blockers.add("原报工模式不可识别，不能启用聚合数量部分纠正");
            return;
        }
        if (mode != ReportModeEnum.QUANTITY && mode != ReportModeEnum.PACKAGE_QUANTITY) {
            blockers.add("部分数量纠正仅支持数量报工和包装数量报工；逐件/流转卡请按标识撤回");
        }
        if (mode == ReportModeEnum.PACKAGE_QUANTITY) {
            List<MesWrapRecordEntity> records = wrapRecordService.list(new LambdaQueryWrapper<MesWrapRecordEntity>()
                    .eq(MesWrapRecordEntity::getReportId, report.getId()).orderByAsc(MesWrapRecordEntity::getId)
                    .last(lock ? "FOR UPDATE" : ""));
            if (records.size() != 1) {
                blockers.add("包装数量报工缺少唯一原始包装内容行");
            } else if ("PACKED".equals(records.get(0).getPackingStatus())) {
                blockers.add("包装仍处于封箱状态，须先拆箱核对实物");
            } else if (!Set.of("UNPACKED", "PARTIAL").contains(records.get(0).getPackingStatus())) {
                blockers.add("包装内容已移动、重组或失效，不能在原包装报工上部分纠正");
            }
        }
    }

    /**
     * 计算锚点报工在当前工艺DAG中的同标识有效下游报工。
     */
    private ChainPlan chainPlan(MesTaskReportEntity anchorReport, String factory, String originalVersion) {
        MesTaskArrangeEntity anchorArrange = arrangeService.getOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, anchorReport.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, factory)
                .eq(MesTaskArrangeEntity::getCode, anchorReport.getArrangeCode()), false);
        require(anchorArrange != null, "原报工对应的派工任务不存在");
        MesReportReverseNodeEvidence.assertReport(anchorReport, anchorArrange);
        List<MesProduceTaskEntity> tasks = produceTaskService.list(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getTenantId, anchorReport.getTenantId())
                .eq(MesProduceTaskEntity::getFactoryCode, factory)
                .eq(MesProduceTaskEntity::getTaskCode, anchorReport.getTaskCode()));
        boolean nodeEvidence = originalVersion == null
                ? MesFrozenExecutionGraph.NODE_EXECUTION_V1.equals(anchorArrange.getRouteExecutionSchemaVersion())
                : Set.of(MesReportReverseNodeEvidence.NODE_VERSION, MesReportReverseNodeEvidence.BINDING_NODE_VERSION).contains(originalVersion);
        MesFrozenExecutionGraph graph = nodeEvidence ? MesFrozenExecutionGraph.of(tasks) : null;
        List<Map<String, String>> executionNodes = nodeEvidence
                ? MesReportReverseNodeEvidence.executionNodes(tasks) : List.of();
        require(nodeEvidence || (tasks.stream().map(MesProduceTaskEntity::getRouteCode).distinct().count() == tasks.size()
                        && tasks.stream().allMatch(item -> StrUtil.isNotBlank(item.getRouteCode()))),
                "实际工艺DAG含重复/缺失工序编码，禁止吞并节点后执行链式撤回");
        Map<String, MesProduceTaskEntity> byRoute = nodeEvidence ? Map.of() : tasks.stream()
                .filter(item -> StrUtil.isNotBlank(item.getRouteCode()))
                .collect(java.util.stream.Collectors.toMap(MesProduceTaskEntity::getRouteCode,
                        item -> item, (left, right) -> left, LinkedHashMap::new));
        MesProduceTaskEntity anchorTask = tasks.stream()
                .filter(item -> Objects.equals(item.getCode(), anchorReport.getProduceTaskCode())).findFirst()
                .orElseThrow(() -> new CheckedException("原报工对应的工序任务不存在"));
        if (nodeEvidence) {
            require(MesFrozenExecutionGraph.NODE_EXECUTION_V1.equals(anchorTask.getRouteExecutionSchemaVersion()),
                    "原节点批准合同不能降为历史执行图");
            require(Objects.equals(graph.current(anchorArrange).getId(), anchorTask.getId()),
                    "原报工派工与冻结节点不一致");
        }

        Map<String, Integer> depth = new LinkedHashMap<>();
        Deque<String> pending = new ArrayDeque<>();
        if (nodeEvidence) {
            depth.putAll(graph.descendantDepths(anchorTask));
        } else {
            // 旧申请重采仍按原主工序键、原遍历和原排序生成指纹，不升级原正文。
            for (String code : splitCodes(anchorTask.getSuccessorCodes())) {
                depth.put(code, 1);
                pending.add(code);
            }
        }
        List<String> blockers = new ArrayList<>();
        int traversalCount = 0;
        int traversalLimit = Math.max(100, tasks.size() * Math.max(tasks.size(), 1));
        while (!pending.isEmpty()) {
            if (++traversalCount > traversalLimit) {
                blockers.add("工艺DAG疑似存在循环或异常引用，不能可靠计算完整下游范围");
                break;
            }
            String routeCode = pending.removeFirst();
            if (Objects.equals(routeCode, anchorTask.getRouteCode())) {
                blockers.add("工艺DAG下游重新指向锚点工序，存在循环，不能执行链式撤回");
                continue;
            }
            MesProduceTaskEntity task = byRoute.get(routeCode);
            if (task == null) {
                blockers.add("工艺DAG下游工序[" + routeCode + "]缺失，不能冻结完整影响链");
                continue;
            }
            int currentDepth = depth.get(routeCode);
            for (String successor : splitCodes(task.getSuccessorCodes())) {
                int nextDepth = currentDepth + 1;
                Integer old = depth.get(successor);
                if (old == null || nextDepth > old) {
                    depth.put(successor, nextDepth);
                    pending.add(successor);
                }
            }
        }

        ReportModeEnum reportMode = null;
        try {
            reportMode = ReportModeEnum.fromCodes(anchorReport.getReportMode(), anchorArrange.getRouteType());
        } catch (IllegalArgumentException ignored) {
            blockers.add("原报工缺少可识别的冻结报工模式，不能证明逐标识影响范围");
        }
        boolean supported = reportMode == ReportModeEnum.ONE_CODE || reportMode == ReportModeEnum.TRANSFER_CARD;
        boolean aggregateNode = nodeEvidence && (reportMode == ReportModeEnum.QUANTITY
                || reportMode == ReportModeEnum.PACKAGE_QUANTITY || reportMode == null);
        if (supported && StrUtil.isBlank(anchorReport.getTransferCode())) {
            supported = false;
            blockers.add("逐标识链式撤回缺少规范生产标识");
        }

        ChainCandidate anchor = new ChainCandidate(anchorReport, anchorArrange, 0);
        if (depth.isEmpty()) {
            return new ChainPlan(anchor, List.of(), supported, blockers, nodeEvidence, executionNodes);
        }
        List<String> downstreamTaskCodes = nodeEvidence ? List.copyOf(depth.keySet())
                : depth.keySet().stream().map(byRoute::get)
                .filter(Objects::nonNull).map(MesProduceTaskEntity::getCode).toList();
        if (downstreamTaskCodes.isEmpty()) {
            return new ChainPlan(anchor, List.of(), supported, blockers, nodeEvidence, executionNodes);
        }
        if (nodeEvidence && (aggregateNode || StrUtil.isBlank(anchorReport.getTransferCode())) && tasks.stream()
                .filter(task -> downstreamTaskCodes.contains(task.getCode()))
                .anyMatch(task -> Set.of("PRODUCT", "PAUSE", "FINISH").contains(task.getStatus()))) {
            blockers.add("下游节点已开工或完成，数量报工无法证明批次归属，不能冲销或部分纠正");
        }
        List<MesTaskArrangeEntity> downstreamArranges = arrangeService.list(
                new LambdaQueryWrapper<MesTaskArrangeEntity>()
                        .eq(MesTaskArrangeEntity::getTenantId, anchorReport.getTenantId())
                        .eq(MesTaskArrangeEntity::getFactoryCode, factory)
                        .eq(MesTaskArrangeEntity::getTaskCode, anchorReport.getTaskCode())
                        .in(MesTaskArrangeEntity::getProduceTaskCode, downstreamTaskCodes));
        if (nodeEvidence) {
            downstreamArranges.forEach(graph::current);
        }
        if (supported) {
            List<String> aggregateArrangeCodes = downstreamArranges.stream().filter(arrange -> {
                try {
                    ReportModeEnum mode = ReportModeEnum.fromCodes(arrange.getReportMode(), arrange.getRouteType());
                    return mode != ReportModeEnum.ONE_CODE && mode != ReportModeEnum.TRANSFER_CARD;
                } catch (IllegalArgumentException exception) {
                    return true;
                }
            }).map(MesTaskArrangeEntity::getCode).toList();
            if (!aggregateArrangeCodes.isEmpty() && reportService.count(
                    new LambdaQueryWrapper<MesTaskReportEntity>()
                            .eq(MesTaskReportEntity::getFactoryCode, factory)
                            .eq(MesTaskReportEntity::getTaskCode, anchorReport.getTaskCode())
                            .in(MesTaskReportEntity::getArrangeCode, aggregateArrangeCodes)
                            .eq(MesTaskReportEntity::getStatus, ReportStatusEnum.EFFECTIVE.name())) > 0) {
                blockers.add("下游存在数量或包装聚合报工，无法证明其中不包含当前标识，禁止自动链式撤回");
            }
            if (reportService.count(new LambdaQueryWrapper<MesTaskReportEntity>()
                    .eq(MesTaskReportEntity::getFactoryCode, factory)
                    .eq(MesTaskReportEntity::getTaskCode, anchorReport.getTaskCode())
                    .in(MesTaskReportEntity::getProduceTaskCode, downstreamTaskCodes)
                    .eq(MesTaskReportEntity::getTransferCode, anchorReport.getTransferCode())
                    .eq(MesTaskReportEntity::getStatus, ReportStatusEnum.EFFECTIVE.name())
                    .eq(MesTaskReportEntity::getExecutionKind,
                            MesIdentifierRouteReturnCoordinator.ROUTE_RETURN)) > 0) {
                blockers.add("该标识仍有活动退回路线报工，必须先完成或处置路线版本，不能与正常链混合撤回");
            }
        }
        LambdaQueryWrapper<MesTaskReportEntity> query = new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getFactoryCode, factory)
                .eq(MesTaskReportEntity::getTaskCode, anchorReport.getTaskCode())
                .in(MesTaskReportEntity::getProduceTaskCode, downstreamTaskCodes)
                .eq(MesTaskReportEntity::getStatus, ReportStatusEnum.EFFECTIVE.name())
                .and(item -> item.isNull(MesTaskReportEntity::getExecutionKind)
                        .or().eq(MesTaskReportEntity::getExecutionKind, "NORMAL"));
        if (!aggregateNode && StrUtil.isNotBlank(anchorReport.getTransferCode())) {
            query.eq(MesTaskReportEntity::getTransferCode, anchorReport.getTransferCode());
        }
        List<MesTaskReportEntity> downstreamReports = reportService.list(query.orderByAsc(
                MesTaskReportEntity::getReportTime).orderByAsc(MesTaskReportEntity::getId).last("LIMIT 52"));
        if (downstreamReports.size() > 50) {
            blockers.add("同一影响链超过50笔报工，禁止截断自动撤回，请先治理重复或异常报工");
            downstreamReports = downstreamReports.subList(0, 50);
        }
        if (downstreamReports.isEmpty()) {
            return new ChainPlan(anchor, List.of(), supported, List.copyOf(blockers), nodeEvidence, executionNodes);
        }

        Map<String, Long> countByTask = downstreamReports.stream().collect(java.util.stream.Collectors.groupingBy(
                MesTaskReportEntity::getProduceTaskCode, LinkedHashMap::new,
                java.util.stream.Collectors.counting()));
        countByTask.forEach((taskCode, count) -> {
            if (count > 1) blockers.add("同一标识在工序任务[" + taskCode + "]存在" + count
                    + "笔有效报工，无法唯一解释工序链");
        });

        Map<String, MesTaskArrangeEntity> arrangeByCode = arrangeService.list(
                        new LambdaQueryWrapper<MesTaskArrangeEntity>()
                                .eq(MesTaskArrangeEntity::getTenantId, anchorReport.getTenantId())
                                .eq(MesTaskArrangeEntity::getFactoryCode, factory)
                                .in(MesTaskArrangeEntity::getCode,
                                        downstreamReports.stream().map(MesTaskReportEntity::getArrangeCode).distinct().toList()))
                .stream().collect(java.util.stream.Collectors.toMap(MesTaskArrangeEntity::getCode,
                        item -> item, (left, right) -> {
                            throw new CheckedException("下游实际派工编码不唯一，禁止取首条推断范围");
                        }, LinkedHashMap::new));
        List<ChainCandidate> downstream = new ArrayList<>();
        for (MesTaskReportEntity report : downstreamReports) {
            MesTaskArrangeEntity arrange = arrangeByCode.get(report.getArrangeCode());
            if (arrange == null) {
                blockers.add("下游报工[" + report.getId() + "]对应派工不存在");
                continue;
            }
            MesReportReverseNodeEvidence.assertReport(report, arrange);
            if (nodeEvidence) {
                graph.current(arrange);
            }
            try {
                ReportModeEnum downstreamMode = ReportModeEnum.fromCodes(arrange.getReportMode(), arrange.getRouteType());
                if (downstreamMode != ReportModeEnum.ONE_CODE && downstreamMode != ReportModeEnum.TRANSFER_CARD) {
                    blockers.add("下游工序[" + arrange.getRouteName() + "]不是逐标识报工，不能纳入自动链式撤回");
                }
            } catch (IllegalArgumentException exception) {
                blockers.add("下游工序[" + arrange.getRouteName() + "]缺少可识别的冻结报工模式");
            }
            MesProduceTaskEntity task = tasks.stream()
                    .filter(item -> Objects.equals(item.getCode(), report.getProduceTaskCode())).findFirst().orElse(null);
            downstream.add(new ChainCandidate(report, arrange,
                    task == null ? Integer.MAX_VALUE : depth.getOrDefault(
                            nodeEvidence ? task.getCode() : task.getRouteCode(), Integer.MAX_VALUE)));
        }
        Comparator<ChainCandidate> order = Comparator.comparingInt(ChainCandidate::depth).reversed();
        if (!nodeEvidence) {
            order = order.thenComparing(candidate -> Objects.requireNonNullElse(candidate.arrange().getRouteOrder(), 0),
                    Comparator.reverseOrder());
        }
        downstream.sort(order.thenComparing(candidate -> candidate.report().getId(), Comparator.reverseOrder()));
        return new ChainPlan(anchor, List.copyOf(downstream), supported, List.copyOf(blockers), nodeEvidence, executionNodes);
    }

    private List<String> splitCodes(String value) {
        if (StrUtil.isBlank(value)) return List.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(StrUtil::isNotBlank).distinct().toList();
    }

    private void saveScopes(MesReportReverseRequestEntity request, CorrectionEvidence evidence) {
        int sequence = 1;
        for (ChainStep step : evidence.steps()) {
            MesReportReverseScopeEntity scope = new MesReportReverseScopeEntity()
                    .setReverseRequestId(request.getId()).setReportId(step.report().getId())
                    .setSequenceNo(sequence++)
                    .setRouteCode(StrUtil.blankToDefault(step.arrange().getRouteCode(),
                            step.report().getProduceTaskCode()))
                    .setRouteName(StrUtil.blankToDefault(step.arrange().getRouteName(),
                            StrUtil.blankToDefault(step.arrange().getRouteCode(), step.report().getProduceTaskCode())))
                    .setRouteOrder(step.arrange().getRouteOrder())
                    .setEvidenceFingerprint(step.evidence().fingerprint())
                    .setChildReverseRequestId("RCH-" + hash(request.getSubmitRequestId() + "|"
                            + step.report().getId()).substring(0, 60))
                    .setActive(Boolean.TRUE).setFactoryCode(request.getFactoryCode());
            require(scopeService.save(scope), "报工冲销冻结范围保存失败");
        }
    }

    private List<MesReportReverseScopeEntity> scopes(Long requestId) {
        return scopeService.list(new LambdaQueryWrapper<MesReportReverseScopeEntity>()
                .eq(MesReportReverseScopeEntity::getReverseRequestId, requestId)
                .orderByAsc(MesReportReverseScopeEntity::getSequenceNo));
    }

    private boolean scopeMatches(MesReportReverseRequestEntity request, CorrectionEvidence current) {
        List<MesReportReverseScopeEntity> frozen = scopes(request.getId());
        if (frozen.isEmpty()) return StrUtil.isBlank(request.getCorrectionMode());
        return frozen.size() == current.steps().size()
                && java.util.stream.IntStream.range(0, frozen.size()).allMatch(index ->
                Objects.equals(frozen.get(index).getReportId(), current.steps().get(index).report().getId())
                        && Objects.equals(frozen.get(index).getEvidenceFingerprint(),
                        current.steps().get(index).evidence().fingerprint())
                        && Boolean.TRUE.equals(frozen.get(index).getActive()));
    }

    private void deactivateScopes(Long requestId) {
        scopeService.update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MesReportReverseScopeEntity>()
                .eq(MesReportReverseScopeEntity::getReverseRequestId, requestId)
                .eq(MesReportReverseScopeEntity::getActive, Boolean.TRUE)
                .set(MesReportReverseScopeEntity::getActive, Boolean.FALSE));
    }

    private void fillReview(MesReportReverseRequestEntity request, boolean approved,
                            MesReportReverseRequestDTO.ReviewCommand command, String fingerprint,
                            MomUser user, LocalDateTime now) {
        request.setReviewRequestId(command.getOperationRequestId().trim()).setReviewFingerprint(fingerprint)
                .setApproved(approved).setReviewReason(command.getReason().trim()).setReviewerId(user.getId())
                .setReviewerCode(user.getUsername()).setReviewerName(name(user)).setReviewedTime(now);
    }

    private MesReportReverseRequestDTO.Evidence toEvidence(CorrectionEvidence selected,
                                                           MesReportReverseRequestEntity active,
                                                           List<String> blockers) {
        MesTaskReportEntity report = selected.anchor().report();
        boolean supported = report.getEffectiveReportNum() != null && report.getCorrectedReportNum() != null;
        try {
            ReportModeEnum mode = ReportModeEnum.fromCodes(report.getReportMode(),
                    selected.steps().get(0).arrange().getRouteType());
            supported = supported && (mode == ReportModeEnum.QUANTITY || mode == ReportModeEnum.PACKAGE_QUANTITY);
        } catch (Exception exception) {
            supported = false;
        }
        BigDecimal effective = effectiveQuantity(report);
        return new MesReportReverseRequestDTO.Evidence().setReport(report)
                .setCorrectionMode(selected.mode())
                .setChainReports(selected.steps().stream().map(step -> new MesReportReverseRequestDTO.ChainReport()
                        .setReportId(step.report().getId()).setArrangeCode(step.report().getArrangeCode())
                        .setProduceTaskCode(step.report().getProduceTaskCode())
                        .setRouteExecutionSchemaVersion(step.arrange().getRouteExecutionSchemaVersion())
                        .setSourceRouteNodeCode(step.arrange().getSourceRouteNodeCode())
                        .setRouteCode(step.arrange().getRouteCode()).setRouteName(step.arrange().getRouteName())
                        .setRouteOrder(step.arrange().getRouteOrder()).setReportNum(step.report().getReportNum())
                        .setReportTime(step.report().getReportTime())).toList())
                .setEvidenceFingerprint(selected.fingerprint()).setEligible(blockers.isEmpty())
                .setBlockers(blockers).setActiveRequest(active)
                .setEffectiveQuantity(effective)
                .setMaxPartialCorrectionQuantity(supported ? effective : BigDecimal.ZERO)
                .setPartialCorrectionSupported(supported)
                .setPartialCorrectionMessage(supported
                        ? "允许申请小于当前有效数量的部分纠正；包装数量报工须先拆箱"
                        : "当前记录不是带有效数量谱系的数量/包装数量报工");
    }

    private MesReportReverseRequestEntity request(Long id, String factory, boolean lock) {
        require(id != null, "请选择冲销申请");
        MesReportReverseRequestEntity request = getOne(new LambdaQueryWrapper<MesReportReverseRequestEntity>()
                .eq(MesReportReverseRequestEntity::getId, id)
                .eq(MesReportReverseRequestEntity::getFactoryCode, factory)
                .last(lock ? "FOR UPDATE" : ""), false);
        require(request != null, "当前租户和工厂下不存在该冲销申请");
        return request;
    }

    private MesReportReverseRequestEntity activeRequest(List<Long> reportIds, boolean lock) {
        if (reportIds.isEmpty()) return null;
        MesReportReverseScopeEntity occupied = scopeService.getOne(
                new LambdaQueryWrapper<MesReportReverseScopeEntity>()
                        .in(MesReportReverseScopeEntity::getReportId, reportIds)
                        .eq(MesReportReverseScopeEntity::getActive, Boolean.TRUE)
                        .orderByDesc(MesReportReverseScopeEntity::getId).last("LIMIT 1"), false);
        if (occupied != null) return request(occupied.getReverseRequestId(), factoryAccess.require("read"), lock);
        return getOne(new LambdaQueryWrapper<MesReportReverseRequestEntity>()
                .in(MesReportReverseRequestEntity::getReportId, reportIds)
                .eq(MesReportReverseRequestEntity::getStatus, "SUBMITTED")
                .orderByDesc(MesReportReverseRequestEntity::getId)
                .last(lock ? "LIMIT 1 FOR UPDATE" : "LIMIT 1"), false);
    }

    private void saveOperation(MesReportReverseRequestEntity request, String requestId, String fingerprint,
                               String type, String before, String after, String content,
                               MomUser user, LocalDateTime now) {
        MesReportReverseOperationEntity operation = new MesReportReverseOperationEntity()
                .setReverseRequestId(request.getId()).setRequestId(requestId).setRequestFingerprint(fingerprint)
                .setOperationType(type).setBeforeStatus(before).setAfterStatus(after).setContent(content)
                .setOperatorId(user.getId()).setOperatorCode(user.getUsername()).setOperatorName(name(user))
                .setOperationTime(now).setFactoryCode(request.getFactoryCode());
        require(operationMapper.insert(operation) == 1, "冲销操作履历保存失败");
    }

    private void validateSubmit(MesReportReverseRequestDTO.SubmitCommand command) {
        require(command != null && command.getReportId() != null && validRequestId(command.getRequestId())
                        && validFingerprint(command.getExpectedEvidenceFingerprint()) && validReason(command.getReason()),
                "冲销申请必须提供原报工、永久请求号、事实指纹和1至500字原因");
    }

    private void validateReview(MesReportReverseRequestDTO.ReviewCommand command) {
        require(command != null && command.getRequestId() != null && command.getApproved() != null
                        && validRequestId(command.getOperationRequestId())
                        && validFingerprint(command.getExpectedEvidenceFingerprint()) && validReason(command.getReason()),
                "冲销审批必须提供申请、永久请求号、冻结指纹、结论和1至500字依据");
    }

    private MomUser actor() {
        MomUser user = SecurityUtils.getUser();
        require(user != null && user.getId() != null && StrUtil.isNotBlank(user.getUsername()), "报工冲销必须使用实名用户");
        return user;
    }

    private String requestNo(String requestId, LocalDateTime now) {
        return "RRC" + now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + hash(requestId).substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private boolean validRequestId(String value) {
        return StrUtil.isNotBlank(value) && value.trim().length() <= 64;
    }

    private boolean validFingerprint(String value) {
        return StrUtil.isNotBlank(value) && value.trim().length() == 64;
    }

    private boolean validReason(String value) {
        return StrUtil.isNotBlank(value) && value.trim().length() <= 500;
    }

    private String normalizeCorrectionMode(String value, boolean allowAuto) {
        if (StrUtil.isBlank(value)) return allowAuto ? null : "SINGLE";
        String mode = value.trim().toUpperCase(Locale.ROOT);
        require(Set.of("SINGLE", "IDENTIFIER_CHAIN", "PARTIAL_QUANTITY").contains(mode),
                "纠正范围只支持SINGLE、IDENTIFIER_CHAIN或PARTIAL_QUANTITY");
        return mode;
    }

    private BigDecimal effectiveQuantity(MesTaskReportEntity report) {
        return Objects.requireNonNullElse(report.getEffectiveReportNum(), report.getReportNum());
    }

    private String name(MomUser user) {
        return StrUtil.blankToDefault(user.getName(), user.getUsername());
    }

    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Objects.toString(value, "").getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("生成业务指纹失败", exception);
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    private record EvidenceSnapshot(MesTaskReportEntity report, List<String> blockers,
                                    String fingerprint, String json,
                                    MesIdentifierBindingInverseCoordinator.Evidence dynamic) {
    }

    private record ChainCandidate(MesTaskReportEntity report, MesTaskArrangeEntity arrange, int depth) {
    }

    private record ChainPlan(ChainCandidate anchor, List<ChainCandidate> downstream,
                             boolean supported, List<String> blockers, boolean nodeEvidence,
                             List<Map<String, String>> executionNodes) {
        private List<ChainCandidate> all() {
            List<ChainCandidate> rows = new ArrayList<>(downstream);
            rows.add(anchor);
            return rows;
        }
    }

    private record ChainStep(MesTaskReportEntity report, MesTaskArrangeEntity arrange,
                             int depth, EvidenceSnapshot evidence) {
    }

    private record CorrectionEvidence(EvidenceSnapshot anchor, String mode, List<ChainStep> steps,
                                      List<String> blockers, String fingerprint, String json) {
        private List<Long> reportIds() {
            return steps.stream().map(step -> step.report().getId()).toList();
        }
    }
}
