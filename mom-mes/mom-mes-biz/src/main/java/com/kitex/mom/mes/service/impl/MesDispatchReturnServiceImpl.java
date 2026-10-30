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
import com.kitex.mom.mes.dto.MesDispatchReturnDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.MesProduceTaskStatusEnum;
import com.kitex.mom.mes.enums.MesTaskStatusEnum;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.MesDispatchReturnService;
import com.kitex.mom.mes.service.MesIdentifierBindingGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 已接收未开工派工的受控退派实现。
 *
 * <p>该流程只回收尚未形成生产事实的派工占用，不处理已开工、报工、投料或工装寿命事实。
 * 后者必须进入现场停止、退料和冲销链，不能用退派覆盖。</p>
 */
@Service
@RequiredArgsConstructor
public class MesDispatchReturnServiceImpl
        extends ServiceImpl<MesDispatchReturnRequestMapper, MesDispatchReturnRequestEntity>
        implements MesDispatchReturnService {

    private static final Set<String> ACTIVE_ARRANGE_STATUSES = Set.of(
            MesProduceTaskStatusEnum.UN_RECEIVE.name(), MesProduceTaskStatusEnum.RECEIVE.name(),
            MesProduceTaskStatusEnum.PRODUCT.name(), MesProduceTaskStatusEnum.PAUSE.name());

    private final MesDispatchReturnOperationMapper operationMapper;
    private final MesTaskArrangeMapper arrangeMapper;
    private final MesProduceTaskMapper produceTaskMapper;
    private final MesTaskMapper taskMapper;
    private final MesTaskReportMapper reportMapper;
    private final MesWarehouseFeedMapper feedMapper;
    private final MesTaskExecutionSegmentMapper executionSegmentMapper;
    private final MesToolingExecutionMapper toolingExecutionMapper;
    private final MesChangeoverExecutionMapper changeoverExecutionMapper;
    private final MesActualFactoryAccessService factoryAccess;
    private final MesExecutionTaskLock taskLock;
    private final MesIdentifierBindingGroupService bindingGroups;
    private final MesTerminalExecutionGuard terminalExecutionGuard;
    private final MesDispatchCorrectionMapper correctionMapper;

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public IPage<MesDispatchReturnRequestEntity> pageRequests(Page<MesDispatchReturnRequestEntity> page,
                                                              MesDispatchReturnRequestEntity query) {
        String factory = factoryAccess.require("read");
        if (page.getCurrent() < 1 || page.getSize() < 1 || page.getSize() > 100) {
            throw new CheckedException("页码或分页大小无效");
        }
        MesDispatchReturnRequestEntity filter = query == null ? new MesDispatchReturnRequestEntity() : query;
        boolean exactArrange = filter.getArrangeId() != null;
        return page(page, new LambdaQueryWrapper<MesDispatchReturnRequestEntity>()
                .eq(MesDispatchReturnRequestEntity::getFactoryCode, factory)
                .eq(exactArrange, MesDispatchReturnRequestEntity::getArrangeId, filter.getArrangeId())
                .eq(StrUtil.isNotBlank(filter.getStatus()), MesDispatchReturnRequestEntity::getStatus,
                        StrUtil.trim(filter.getStatus()))
                .eq(exactArrange, MesDispatchReturnRequestEntity::getArrangeCode, filter.getArrangeCode())
                .eq(exactArrange, MesDispatchReturnRequestEntity::getTaskCode, filter.getTaskCode())
                .like(!exactArrange && StrUtil.isNotBlank(filter.getArrangeCode()), MesDispatchReturnRequestEntity::getArrangeCode,
                        StrUtil.trim(filter.getArrangeCode()))
                .like(!exactArrange && StrUtil.isNotBlank(filter.getTaskCode()), MesDispatchReturnRequestEntity::getTaskCode,
                        StrUtil.trim(filter.getTaskCode()))
                .like(StrUtil.isNotBlank(filter.getApplicantName()), MesDispatchReturnRequestEntity::getApplicantName,
                        StrUtil.trim(filter.getApplicantName()))
                .orderByDesc(MesDispatchReturnRequestEntity::getSubmittedTime)
                .orderByDesc(MesDispatchReturnRequestEntity::getId));
    }

    @Override
    @Transactional(readOnly = true)
    public MesDispatchReturnDTO.Evidence preview(Long arrangeId) {
        String factory = factoryAccess.require("read");
        EvidenceSnapshot snapshot = evidence(arrangeId, factory, false);
        MesDispatchReturnRequestEntity active = activeRequest(arrangeId, false);
        List<String> blockers = new ArrayList<>(snapshot.blockers());
        if (active != null) {
            blockers.add("该派工已有待审核退派申请：" + active.getRequestNo());
        }
        return toEvidence(snapshot, active, blockers);
    }

    @Override
    @Transactional(readOnly = true)
    public MesDispatchReturnDTO.Detail detail(Long requestId) {
        String factory = factoryAccess.require("read");
        MesDispatchReturnRequestEntity request = request(requestId, factory, false);
        EvidenceSnapshot snapshot = evidence(request.getArrangeId(), factory, false);
        List<MesDispatchReturnOperationEntity> operations = operationMapper.selectList(
                new LambdaQueryWrapper<MesDispatchReturnOperationEntity>()
                        .eq(MesDispatchReturnOperationEntity::getReturnRequestId, request.getId())
                        .orderByAsc(MesDispatchReturnOperationEntity::getId));
        return new MesDispatchReturnDTO.Detail().setRequest(request)
                .setCurrentEvidence(toEvidence(snapshot, activeRequest(request.getArrangeId(), false),
                        snapshot.blockers()))
                .setOperations(operations);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesDispatchReturnRequestEntity submit(MesDispatchReturnDTO.SubmitCommand command) {
        String factory = factoryAccess.require("write");
        validateSubmit(command);
        String requestId = command.getRequestId().trim();
        String fingerprint = hash(List.of(command.getArrangeId(), command.getExpectedEvidenceFingerprint().trim(),
                command.getReason().trim(), actor().getUsername()));
        baseMapper.lockKey("COMMAND:" + requestId);
        MesDispatchReturnRequestEntity replay = getOne(new LambdaQueryWrapper<MesDispatchReturnRequestEntity>()
                .eq(MesDispatchReturnRequestEntity::getSubmitRequestId, requestId), false);
        if (replay != null) {
            require(Objects.equals(replay.getSubmitFingerprint(), fingerprint), "退派提交请求号已被不同命令使用");
            return replay;
        }
        MesTaskArrangeEntity ownership = taskLock.arrange(command.getArrangeId());
        baseMapper.lockKey("ARRANGE:" + command.getArrangeId());
        EvidenceSnapshot snapshot = evidence(command.getArrangeId(), factory, true);
        taskLock.assertArrange(ownership, snapshot.arrange());
        terminalExecutionGuard.assertUnoccupied(snapshot.arrange());
        require(snapshot.blockers().isEmpty(), String.join("；", snapshot.blockers()));
        require(Objects.equals(snapshot.fingerprint(), command.getExpectedEvidenceFingerprint().trim()),
                "派工事实已变化，请刷新退派预览后重新提交");
        require(activeRequest(command.getArrangeId(), true) == null, "该派工已有待审核退派申请");
        require(correctionMapper.pending(snapshot.arrange().getTenantId(), factory, command.getArrangeId()) == 0,
                "该派工已有待审核修正申请");

        MomUser user = actor();
        LocalDateTime now = LocalDateTime.now();
        MesDispatchReturnRequestEntity request = new MesDispatchReturnRequestEntity()
                .setRequestNo(requestNo(requestId, now)).setSubmitRequestId(requestId)
                .setSubmitFingerprint(fingerprint).setArrangeId(snapshot.arrange().getId())
                .setArrangeCode(snapshot.arrange().getCode()).setTaskCode(snapshot.arrange().getTaskCode())
                .setProduceTaskCode(snapshot.arrange().getProduceTaskCode())
                .setOriginalStatus(snapshot.arrange().getStatus()).setStatus("SUBMITTED")
                .setEvidenceFingerprint(snapshot.fingerprint()).setEvidenceSnapshot(snapshot.json())
                .setReason(command.getReason().trim()).setApplicantId(user.getId())
                .setApplicantCode(user.getUsername()).setApplicantName(user.getName())
                .setSubmittedTime(now).setFactoryCode(factory);
        require(save(request), "退派申请保存失败");
        saveOperation(request, requestId, fingerprint, "SUBMIT", null, "SUBMITTED",
                command.getReason().trim(), user.getId(), user.getUsername(), user.getName(), now);
        return request;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesDispatchReturnRequestEntity review(MesDispatchReturnDTO.ReviewCommand command) {
        String factory = factoryAccess.require("write");
        validateReview(command);
        MomUser user = actor();
        String operationRequestId = command.getOperationRequestId().trim();
        String operationFingerprint = hash(List.of(command.getRequestId(), command.getApproved(),
                command.getExpectedEvidenceFingerprint(), command.getReason().trim(), user.getUsername()));
        baseMapper.lockKey("COMMAND:" + operationRequestId);
        MesDispatchReturnOperationEntity replay = operationMapper.selectOne(
                new LambdaQueryWrapper<MesDispatchReturnOperationEntity>()
                        .eq(MesDispatchReturnOperationEntity::getRequestId, operationRequestId), false);
        if (replay != null) {
            require(Objects.equals(replay.getRequestFingerprint(), operationFingerprint),
                    "退派审批请求号已被不同命令使用");
            return getById(replay.getReturnRequestId());
        }

        MesDispatchReturnRequestEntity owner = request(command.getRequestId(), factory, false);
        bindingGroups.lockDispatchTask(owner.getTaskCode());
        MesDispatchReturnRequestEntity request = request(command.getRequestId(), factory, true);
        taskLock.assertTaskCode(owner.getTaskCode(), request.getTaskCode());
        require("SUBMITTED".equals(request.getStatus()), "只有待审核退派申请可以审批");
        require(!Objects.equals(request.getApplicantId(), user.getId())
                        && !Objects.equals(request.getApplicantCode(), user.getUsername()),
                "退派申请人与审批人必须分离");
        LocalDateTime now = LocalDateTime.now();
        String before = request.getStatus();
        request.setReviewRequestId(operationRequestId).setReviewFingerprint(operationFingerprint)
                .setApproved(command.getApproved()).setReviewReason(command.getReason().trim())
                .setReviewerId(user.getId()).setReviewerCode(user.getUsername()).setReviewerName(user.getName())
                .setReviewedTime(now);

        if (!Boolean.TRUE.equals(command.getApproved())) {
            request.setStatus("REJECTED").setHandledTime(now).setResultMessage("退派申请已驳回");
            require(updateById(request), "退派驳回结果保存失败");
            saveOperation(request, operationRequestId, operationFingerprint, "REJECT", before, "REJECTED",
                    command.getReason().trim(), user.getId(), user.getUsername(), user.getName(), now);
            return request;
        }

        require(Objects.equals(request.getEvidenceFingerprint(), command.getExpectedEvidenceFingerprint().trim()),
                "审批页面证据与原申请不一致，请刷新");
        baseMapper.lockKey("ARRANGE:" + request.getArrangeId());
        EvidenceSnapshot current = evidence(request.getArrangeId(), factory, true);
        terminalExecutionGuard.assertUnoccupied(current.arrange());
        if (!current.blockers().isEmpty() || !Objects.equals(request.getEvidenceFingerprint(), current.fingerprint())) {
            request.setStatus("STALE").setApproved(Boolean.FALSE).setHandledTime(now)
                    .setResultMessage(current.blockers().isEmpty()
                            ? "派工、资源或现场事实已变化，原退派申请失效"
                            : String.join("；", current.blockers()));
            require(updateById(request), "退派失效结果保存失败");
            saveOperation(request, operationRequestId, operationFingerprint, "MARK_STALE", before, "STALE",
                    request.getResultMessage(), user.getId(), user.getUsername(), user.getName(), now);
            return request;
        }

        Map<String, Object> result = executeReturn(current, now);
        request.setStatus("APPROVED").setHandledTime(now).setResultSnapshot(JSONUtil.toJsonStr(result))
                .setResultMessage("原派工已关闭，未投产资源已释放，工序已恢复待派工");
        require(updateById(request), "退派批准结果保存失败");
        saveOperation(request, operationRequestId, operationFingerprint, "APPROVE", before, "APPROVED",
                command.getReason().trim(), user.getId(), user.getUsername(), user.getName(), now);
        return request;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesDispatchReturnRequestEntity withdraw(MesDispatchReturnDTO.WithdrawCommand command) {
        String factory = factoryAccess.require("write");
        require(command != null && command.getRequestId() != null
                        && validRequestId(command.getOperationRequestId()) && validReason(command.getReason()),
                "撤回退派申请必须提供申请、永久请求号和原因");
        MomUser user = actor();
        String commandFingerprint = hash(List.of(command.getRequestId(), command.getReason().trim(), user.getUsername()));
        baseMapper.lockKey("COMMAND:" + command.getOperationRequestId().trim());
        MesDispatchReturnOperationEntity replay = operationMapper.selectOne(
                new LambdaQueryWrapper<MesDispatchReturnOperationEntity>()
                        .eq(MesDispatchReturnOperationEntity::getRequestId, command.getOperationRequestId().trim()), false);
        if (replay != null) {
            require(Objects.equals(replay.getRequestFingerprint(), commandFingerprint), "撤回请求号已被不同命令使用");
            return getById(replay.getReturnRequestId());
        }
        MesDispatchReturnRequestEntity owner = request(command.getRequestId(), factory, false);
        bindingGroups.lockDispatchTask(owner.getTaskCode());
        MesDispatchReturnRequestEntity request = request(command.getRequestId(), factory, true);
        taskLock.assertTaskCode(owner.getTaskCode(), request.getTaskCode());
        require("SUBMITTED".equals(request.getStatus()), "只有待审核退派申请可以撤回");
        require(Objects.equals(request.getApplicantId(), user.getId())
                        && Objects.equals(request.getApplicantCode(), user.getUsername()),
                "只有原申请人可以撤回退派申请");
        LocalDateTime now = LocalDateTime.now();
        request.setStatus("WITHDRAWN").setHandledTime(now).setResultMessage("申请人已撤回退派申请");
        require(updateById(request), "退派申请撤回失败");
        saveOperation(request, command.getOperationRequestId().trim(), commandFingerprint, "WITHDRAW",
                "SUBMITTED", "WITHDRAWN", command.getReason().trim(), user.getId(), user.getUsername(),
                user.getName(), now);
        return request;
    }

    @Override
    @Transactional(readOnly = true)
    public void assertExecutionAllowed(MesTaskArrangeEntity arrange) {
        if (arrange == null || arrange.getId() == null) {
            return;
        }
        require(correctionMapper.pending(arrange.getTenantId(), arrange.getFactoryCode(), arrange.getId()) == 0,
                "派工存在待审核修正申请，不能接收或开工");
        MesDispatchReturnRequestEntity pending = activeRequest(arrange.getId(), false);
        if (pending != null) {
            throw new CheckedException("派工存在待审核退派申请[" + pending.getRequestNo() + "]，不能开工");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void supersedeByPlan(List<MesTaskArrangeEntity> arranges, String planRequestId) {
        if (arranges == null || arranges.isEmpty()) {
            return;
        }
        List<Long> arrangeIds = arranges.stream().map(MesTaskArrangeEntity::getId).filter(Objects::nonNull).toList();
        if (arrangeIds.isEmpty()) {
            return;
        }
        List<MesDispatchReturnRequestEntity> pending = list(new LambdaQueryWrapper<MesDispatchReturnRequestEntity>()
                .in(MesDispatchReturnRequestEntity::getArrangeId, arrangeIds)
                .eq(MesDispatchReturnRequestEntity::getStatus, "SUBMITTED")
                .orderByAsc(MesDispatchReturnRequestEntity::getId).last("FOR UPDATE"));
        LocalDateTime now = LocalDateTime.now();
        for (MesDispatchReturnRequestEntity request : pending) {
            String operationRequestId = "PLAN-SUP-" + hash(planRequestId + '|' + request.getId()).substring(0, 40);
            String operationFingerprint = hash(List.of(request.getId(), planRequestId, "SUPERSEDED"));
            request.setStatus("SUPERSEDED").setHandledTime(now)
                    .setResultMessage("上游已审批计划撤回接管，现场退派申请终止");
            require(updateById(request), "计划撤回接管现场退派申请失败");
            saveOperation(request, operationRequestId, operationFingerprint, "SUPERSEDE_BY_PLAN",
                    "SUBMITTED", "SUPERSEDED", request.getResultMessage(), null, "SYSTEM", "系统", now);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void retireForCorrection(Long correctionId, String expectedSourceFingerprint) {
        String factory = factoryAccess.require("write");
        Long tenant = TenantContextHolder.getTenantId();
        var owner = correctionMapper.request(tenant, factory, correctionId);
        require(owner != null, "缺少当前实际组织的精确修正申请");
        bindingGroups.lockDispatchTask(owner.getTaskCode());
        var correction = correctionMapper.lockedRequest(tenant, factory, correctionId);
        require(correction != null && "SUBMITTED".equals(correction.getStatus())
                && Objects.equals(owner.getTaskCode(), correction.getTaskCode()), "原修正申请已变化");
        var user = actor();
        require(!Objects.equals(correction.getApplicantId(), user.getId())
                && !Objects.equals(correction.getApplicantCode(), user.getUsername()), "修正申请人与审批人必须分离");
        require(Objects.equals(correction.getSourceFingerprint(), expectedSourceFingerprint), "退派依据不属于原修正申请");
        EvidenceSnapshot current = evidence(correction.getArrangeId(), factory, true);
        terminalExecutionGuard.assertUnoccupied(current.arrange());
        require(current.blockers().isEmpty(), String.join("；", current.blockers()));
        require(Objects.equals(current.fingerprint(), correction.getSourceFingerprint())
                        && Objects.equals(current.arrange().getTaskCode(), correction.getTaskCode())
                        && Objects.equals(current.arrange().getProduceTaskCode(), correction.getProduceTaskCode()),
                "原修正派工事实已变化");
        require(activeRequest(correction.getArrangeId(), true) == null, "存在另一待审退派申请");
        require(correctionMapper.workpieceFacts(tenant, factory, correction.getArrangeId()) == 0, "原派工已有工件时间事实");
        executeReturn(current, LocalDateTime.now());
    }

    private Map<String, Object> executeReturn(EvidenceSnapshot snapshot, LocalDateTime handledTime) {
        List<String> releasedToolings = new ArrayList<>();
        for (MesToolingExecutionEntity tooling : snapshot.toolings()) {
            if ("CHECKED_IN".equals(tooling.getStatus())) {
                tooling.setStatus("RELEASED");
                tooling.setReleaseTime(handledTime);
                require(toolingExecutionMapper.updateById(tooling) == 1,
                        "工装[" + tooling.getToolingCode() + "]释放失败");
                releasedToolings.add(tooling.getToolingCode());
            }
        }

        MesTaskArrangeEntity arrange = snapshot.arrange();
        arrange.setStatus(MesProduceTaskStatusEnum.CLOSE.name());
        require(arrangeMapper.updateById(arrange) == 1, "原派工关闭失败");

        long otherActiveForProduce = arrangeMapper.selectCount(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getProduceTaskCode, arrange.getProduceTaskCode())
                .ne(MesTaskArrangeEntity::getId, arrange.getId())
                .in(MesTaskArrangeEntity::getStatus, ACTIVE_ARRANGE_STATUSES));
        MesProduceTaskEntity produceTask = snapshot.produceTask();
        String nextProduceStatus = produceTask.getStatus();
        if (otherActiveForProduce == 0) {
            produceTask.setStatus(MesProduceTaskStatusEnum.WAIT.name());
            require(produceTaskMapper.updateById(produceTask) == 1, "工序任务恢复待派工失败");
            nextProduceStatus = MesProduceTaskStatusEnum.WAIT.name();
        }

        long otherActiveForTask = arrangeMapper.selectCount(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTaskCode, arrange.getTaskCode())
                .ne(MesTaskArrangeEntity::getId, arrange.getId())
                .in(MesTaskArrangeEntity::getStatus, ACTIVE_ARRANGE_STATUSES));
        MesTaskEntity task = snapshot.task();
        String nextTaskStatus = otherActiveForTask == 0
                ? MesTaskStatusEnum.ISSUE.name() : MesTaskStatusEnum.DISPATCH.name();
        if (!Objects.equals(task.getStatus(), nextTaskStatus)) {
            task.setStatus(nextTaskStatus);
            require(taskMapper.updateById(task) == 1, "生产工单状态回退失败");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("arrangeId", arrange.getId());
        result.put("arrangeCode", arrange.getCode());
        result.put("arrangeStatus", MesProduceTaskStatusEnum.CLOSE.name());
        result.put("produceTaskCode", arrange.getProduceTaskCode());
        result.put("produceTaskStatus", nextProduceStatus);
        result.put("taskCode", arrange.getTaskCode());
        result.put("taskStatus", nextTaskStatus);
        result.put("releasedToolingCodes", releasedToolings);
        result.put("handledTime", handledTime);
        return result;
    }

    private EvidenceSnapshot evidence(Long arrangeId, String factory, boolean lock) {
        require(arrangeId != null, "请选择派工任务");
        Long tenantId = TenantContextHolder.getTenantId();
        MesTaskArrangeEntity arrange = arrangeMapper.selectOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getId, arrangeId)
                .eq(MesTaskArrangeEntity::getTenantId, tenantId)
                .eq(MesTaskArrangeEntity::getFactoryCode, factory).last(lock ? "FOR UPDATE" : ""));
        require(arrange != null, "当前租户和工厂下不存在该派工任务");
        MesTaskEntity task = taskMapper.selectOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getCode, arrange.getTaskCode())
                .eq(MesTaskEntity::getTenantId, tenantId)
                .eq(MesTaskEntity::getFactoryCode, factory).last(lock ? "FOR UPDATE" : ""));
        MesProduceTaskEntity produceTask = produceTaskMapper.selectOne(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getCode, arrange.getProduceTaskCode())
                .eq(MesProduceTaskEntity::getTenantId, tenantId)
                .eq(MesProduceTaskEntity::getFactoryCode, factory).last(lock ? "FOR UPDATE" : ""));
        require(task != null && produceTask != null, "派工关联的生产工单或工序任务不存在");
        require(Objects.equals(task.getCode(), produceTask.getTaskCode()), "派工、工序和生产工单归属不一致");

        long reportCount = reportMapper.selectCount(new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getArrangeCode, arrange.getCode()));
        long feedCount = feedMapper.selectCount(new LambdaQueryWrapper<MesWarehouseFeedEntity>()
                .eq(MesWarehouseFeedEntity::getArrangeCode, arrange.getCode()));
        long segmentCount = executionSegmentMapper.selectCount(new LambdaQueryWrapper<MesTaskExecutionSegmentEntity>()
                .eq(MesTaskExecutionSegmentEntity::getArrangeId, arrange.getId()));
        List<MesToolingExecutionEntity> toolings = toolingExecutionMapper.selectList(
                new LambdaQueryWrapper<MesToolingExecutionEntity>()
                        .eq(MesToolingExecutionEntity::getArrangeCode, arrange.getCode())
                        .orderByAsc(MesToolingExecutionEntity::getToolingCode)
                        .orderByAsc(MesToolingExecutionEntity::getId).last(lock ? "FOR UPDATE" : ""));
        long activeChangeoverCount = 0;
        if (StrUtil.isNotBlank(arrange.getPlannedDeviceCode()) && StrUtil.isNotBlank(arrange.getActualWorksiteCode())) {
            activeChangeoverCount = changeoverExecutionMapper.selectCount(
                    new LambdaQueryWrapper<MesChangeoverExecutionEntity>()
                            .eq(MesChangeoverExecutionEntity::getFactoryCode, factory)
                            .eq(MesChangeoverExecutionEntity::getDeviceCode, arrange.getPlannedDeviceCode())
                            .eq(MesChangeoverExecutionEntity::getWorksiteCode, arrange.getActualWorksiteCode())
                            .eq(MesChangeoverExecutionEntity::getStatus, "STARTED"));
        }

        List<String> blockers = blockers(arrange, task, produceTask, reportCount, feedCount,
                segmentCount, toolings, activeChangeoverCount);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("version", "MES_DISPATCH_RETURN_V1");
        facts.put("tenantId", arrange.getTenantId());
        facts.put("factoryCode", arrange.getFactoryCode());
        facts.put("arrange", arrangeFacts(arrange));
        facts.put("task", List.of(task.getId(), task.getCode(), task.getStatus(), value(task.getStartTime()),
                value(task.getFinishTime()), decimal(task.getCompleteNum()), decimal(task.getInStockNum()),
                decimal(task.getUnInStockNum()), decimal(task.getUnPassNum())));
        facts.put("produceTask", List.of(produceTask.getId(), produceTask.getCode(), produceTask.getStatus(),
                value(produceTask.getStartTime()), value(produceTask.getFinishTime()),
                decimal(produceTask.getCompleteNum()), decimal(produceTask.getQualityReleasedNum()),
                decimal(produceTask.getUnPassNum())));
        facts.put("reportCount", reportCount);
        facts.put("feedCount", feedCount);
        facts.put("executionSegmentCount", segmentCount);
        facts.put("activeChangeoverCount", activeChangeoverCount);
        facts.put("toolings", toolings.stream().map(this::toolingFacts).toList());
        String json = JSONUtil.toJsonStr(facts);
        return new EvidenceSnapshot(arrange, task, produceTask, toolings, blockers, hash(json), json);
    }

    private List<String> blockers(MesTaskArrangeEntity arrange, MesTaskEntity task,
                                  MesProduceTaskEntity produceTask, long reportCount, long feedCount,
                                  long segmentCount, List<MesToolingExecutionEntity> toolings,
                                  long activeChangeoverCount) {
        List<String> blockers = new ArrayList<>();
        if (!Set.of("UN_RECEIVE", "RECEIVE").contains(arrange.getStatus())) {
            blockers.add("只有未接收或已接收且未开工的派工可以申请退派，当前状态=" + arrange.getStatus());
        }
        if ("RECEIVE".equals(arrange.getStatus()) && StrUtil.isBlank(arrange.getActualWorksiteCode())) {
            blockers.add("派工缺少接收时确认的实际工位");
        }
        if (arrange.getStartTime() != null || arrange.getFinishTime() != null || positive(arrange.getCompleteNum())
                || positive(arrange.getQualityReleasedNum()) || positive(arrange.getUnPassNum())) {
            blockers.add("派工已形成开工、完工或生产质量数量事实");
        }
        if (!Set.of("UN_RECEIVE", "RECEIVE").contains(produceTask.getStatus())
                || produceTask.getStartTime() != null || produceTask.getFinishTime() != null
                || positive(produceTask.getCompleteNum()) || positive(produceTask.getQualityReleasedNum())
                || positive(produceTask.getUnPassNum())) {
            blockers.add("工序任务已形成开工、完工或生产质量数量事实");
        }
        if (!MesTaskStatusEnum.DISPATCH.name().equals(task.getStatus())) {
            blockers.add("生产工单不处于已派工状态，当前状态=" + task.getStatus());
        }
        if (task.getStartTime() != null || task.getFinishTime() != null || positive(task.getCompleteNum())
                || positive(task.getInStockNum()) || positive(task.getUnInStockNum()) || positive(task.getUnPassNum())) {
            blockers.add("生产工单已形成开工、完工、报工质量或入库数量事实");
        }
        if (reportCount > 0) {
            blockers.add("派工已有报工记录，须走报工冲销闭环");
        }
        if (feedCount > 0) {
            blockers.add("派工已有投料记录，须先完成退料闭环");
        }
        if (segmentCount > 0) {
            blockers.add("派工已有现场执行时间段，不能按未开工退派");
        }
        if (activeChangeoverCount > 0) {
            blockers.add("实际工位和设备存在进行中的换型，须先完成或取消换型");
        }
        for (MesToolingExecutionEntity tooling : toolings) {
            if (!Set.of("CHECKED_IN", "RELEASED").contains(tooling.getStatus())
                    || tooling.getStartTime() != null || tooling.getActualLifeUsage() != null
                    || StrUtil.isNotBlank(tooling.getLifeConsumptionRequestId())) {
                blockers.add("工装[" + tooling.getToolingCode() + "]已投入生产或完成寿命结算");
            }
        }
        return blockers;
    }

    private List<Object> arrangeFacts(MesTaskArrangeEntity arrange) {
        return Arrays.asList(arrange.getId(), arrange.getCode(), arrange.getStatus(), arrange.getTaskCode(),
                arrange.getProduceTaskCode(), arrange.getWorkerCode(), arrange.getActualWorksiteCode(),
                arrange.getWorksiteCodes(), arrange.getPlannedWorksiteCode(), arrange.getPlannedDeviceCode(),
                arrange.getPlannedToolingCodes(), value(arrange.getStartTime()), value(arrange.getFinishTime()),
                decimal(arrange.getCompleteNum()), decimal(arrange.getQualityReleasedNum()),
                decimal(arrange.getUnPassNum()), hash(Objects.toString(arrange.getApsOperationSnapshot(), "")));
    }

    private List<Object> toolingFacts(MesToolingExecutionEntity tooling) {
        return Arrays.asList(tooling.getId(), tooling.getToolingCode(), tooling.getStatus(),
                value(tooling.getCheckInTime()), value(tooling.getStartTime()), value(tooling.getReleaseTime()),
                decimal(tooling.getActualLifeUsage()), tooling.getLifeConsumptionRequestId());
    }

    private MesDispatchReturnDTO.Evidence toEvidence(EvidenceSnapshot snapshot,
                                                     MesDispatchReturnRequestEntity active,
                                                     List<String> blockers) {
        return new MesDispatchReturnDTO.Evidence().setArrange(snapshot.arrange())
                .setTaskStatus(snapshot.task().getStatus())
                .setProduceTaskStatus(snapshot.produceTask().getStatus())
                .setToolingExecutions(snapshot.toolings()).setEvidenceFingerprint(snapshot.fingerprint())
                .setEligible(blockers.isEmpty()).setBlockers(blockers).setActiveRequest(active);
    }

    private MesDispatchReturnRequestEntity request(Long id, String factory, boolean lock) {
        require(id != null, "请选择退派申请");
        MesDispatchReturnRequestEntity request = getOne(new LambdaQueryWrapper<MesDispatchReturnRequestEntity>()
                .eq(MesDispatchReturnRequestEntity::getId, id)
                .eq(MesDispatchReturnRequestEntity::getFactoryCode, factory)
                .last(lock ? "FOR UPDATE" : ""), false);
        require(request != null, "当前租户和工厂下不存在该退派申请");
        return request;
    }

    private MesDispatchReturnRequestEntity activeRequest(Long arrangeId, boolean lock) {
        return getOne(new LambdaQueryWrapper<MesDispatchReturnRequestEntity>()
                .eq(MesDispatchReturnRequestEntity::getArrangeId, arrangeId)
                .eq(MesDispatchReturnRequestEntity::getStatus, "SUBMITTED")
                .orderByDesc(MesDispatchReturnRequestEntity::getId)
                .last(lock ? "LIMIT 1 FOR UPDATE" : "LIMIT 1"), false);
    }

    private void saveOperation(MesDispatchReturnRequestEntity request, String requestId,
                               String requestFingerprint, String operationType, String beforeStatus,
                               String afterStatus, String content, Long operatorId, String operatorCode,
                               String operatorName, LocalDateTime operationTime) {
        MesDispatchReturnOperationEntity operation = new MesDispatchReturnOperationEntity()
                .setReturnRequestId(request.getId()).setRequestId(requestId)
                .setRequestFingerprint(requestFingerprint).setOperationType(operationType)
                .setBeforeStatus(beforeStatus).setAfterStatus(afterStatus).setContent(content)
                .setOperatorId(operatorId).setOperatorCode(operatorCode).setOperatorName(operatorName)
                .setOperationTime(operationTime).setFactoryCode(request.getFactoryCode());
        require(operationMapper.insert(operation) == 1, "退派操作履历保存失败");
    }

    private void validateSubmit(MesDispatchReturnDTO.SubmitCommand command) {
        require(command != null && command.getArrangeId() != null && validRequestId(command.getRequestId())
                        && validFingerprint(command.getExpectedEvidenceFingerprint()) && validReason(command.getReason()),
                "退派申请必须提供派工、永久请求号、当前事实指纹和1至500字原因");
    }

    private void validateReview(MesDispatchReturnDTO.ReviewCommand command) {
        require(command != null && command.getRequestId() != null && validRequestId(command.getOperationRequestId())
                        && validFingerprint(command.getExpectedEvidenceFingerprint())
                        && command.getApproved() != null && validReason(command.getReason()),
                "退派审批必须提供申请、永久请求号、原事实指纹、结论和1至500字依据");
    }

    private boolean validRequestId(String value) {
        return value != null && value.matches("[A-Za-z0-9:_-]{16,64}");
    }

    private boolean validFingerprint(String value) {
        return value != null && value.matches("[a-f0-9]{64}");
    }

    private boolean validReason(String value) {
        return StrUtil.isNotBlank(value) && value.trim().length() <= 500;
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private String decimal(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private String value(Object value) {
        return Objects.toString(value, "");
    }

    private String requestNo(String requestId, LocalDateTime time) {
        return "DR" + time.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + hash(requestId).substring(0, 10).toUpperCase();
    }

    private MomUser actor() {
        MomUser user = SecurityUtils.getUser();
        require(user != null && user.getId() != null && StrUtil.isNotBlank(user.getUsername()), "退派操作必须实名登录");
        return user;
    }

    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSONUtil.toJsonStr(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("退派证据指纹生成失败", ex);
        }
    }

    private record EvidenceSnapshot(MesTaskArrangeEntity arrange, MesTaskEntity task,
                                    MesProduceTaskEntity produceTask,
                                    List<MesToolingExecutionEntity> toolings,
                                    List<String> blockers, String fingerprint, String json) {
    }
}
