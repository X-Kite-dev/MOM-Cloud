package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 零执行普通配额与历史未开工派工的受控退派实现。
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
	private final MesDispatchQuotaReturnSupport quotaReturnSupport;
	private final MesDispatchQuotaReturnMapper quotaReturnMapper;
	private final MesInspectionRevisionHold revisionHolds;

	private static final String LEGACY_EVIDENCE_VERSION = "MES_DISPATCH_RETURN_V1";
	private static final String AUTO_EVIDENCE_VERSION = "AUTO";

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }

	/** {@inheritDoc} */
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
				.eq(MesDispatchReturnRequestEntity::getTenantId, TenantContextHolder.getTenantId())
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

	/** {@inheritDoc} */
	@Override
	@Transactional(readOnly = true)
	public MesDispatchReturnDTO.Evidence preview(Long arrangeId) {
		String factory = factoryAccess.require("read");
		return preview(arrangeId, factory, AUTO_EVIDENCE_VERSION);
	}

	/** {@inheritDoc} */
	@Override
	@Transactional(readOnly = true)
	public MesDispatchReturnDTO.Evidence previewForCorrection(Long arrangeId) {
		return preview(arrangeId, factoryAccess.require("read"), LEGACY_EVIDENCE_VERSION);
	}

	/**
	 * 普通申请自动选择配额证据；修正预览固定V1，并保留目标待审门。
	 */
	private MesDispatchReturnDTO.Evidence preview(Long arrangeId, String factory, String version) {
		EvidenceSnapshot snapshot = evidence(arrangeId, factory, false, version);
		MesDispatchReturnRequestEntity active = activeRequest(arrangeId, factory, false);
		List<String> blockers = new ArrayList<>(snapshot.blockers());
		if (active != null) {
			blockers.add("该派工已有待审核退派申请：" + active.getRequestNo());
		}
		return toEvidence(snapshot, active, blockers);
	}

	/** {@inheritDoc} */
	@Override
	@Transactional(readOnly = true)
	public MesDispatchReturnDTO.Detail detail(Long requestId) {
		String factory = factoryAccess.require("read");
		MesDispatchReturnRequestEntity request = request(requestId, factory, false);
		EvidenceSnapshot snapshot = evidence(request.getArrangeId(), factory, false, evidenceVersion(request));
		List<MesDispatchReturnOperationEntity> operations = operationMapper.selectList(
				new LambdaQueryWrapper<MesDispatchReturnOperationEntity>()
						.eq(MesDispatchReturnOperationEntity::getTenantId, TenantContextHolder.getTenantId())
						.eq(MesDispatchReturnOperationEntity::getFactoryCode, factory)
						.eq(MesDispatchReturnOperationEntity::getReturnRequestId, request.getId())
						.orderByAsc(MesDispatchReturnOperationEntity::getId));
		return new MesDispatchReturnDTO.Detail().setRequest(request)
				.setCurrentEvidence(toEvidence(snapshot, activeRequest(request.getArrangeId(), factory, false),
						snapshot.blockers()))
				.setOperations(operations);
	}

	/** {@inheritDoc} */
	@Override
	@Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
	public MesDispatchReturnRequestEntity submit(MesDispatchReturnDTO.SubmitCommand command) {
		String factory = factoryAccess.require("write");
		validateSubmit(command);
		String requestId = command.getRequestId().trim();
		MomUser user = actor();
		String fingerprint = submitFingerprint(command, user);
		baseMapper.lockKey("COMMAND:" + requestId);
		// 原号先回读：既有成功事实不受后来新增的修订hold或现场状态阻断。
		MesDispatchReturnDTO.Receipt replay = originalReceipt("SUBMIT", requestId, fingerprint,
				null, command.getArrangeId(), null, factory, user);
		if ("APPLIED".equals(replay.getStatus())) {
			return replay.getRequest();
		}
		MesTaskArrangeEntity ownership = taskLock.arrange(command.getArrangeId());
		baseMapper.lockKey("ARRANGE:" + command.getArrangeId());
		EvidenceSnapshot snapshot = evidence(command.getArrangeId(), factory, true, AUTO_EVIDENCE_VERSION);
		taskLock.assertArrange(ownership, snapshot.arrange());
		terminalExecutionGuard.assertUnoccupied(snapshot.arrange());
		require(snapshot.blockers().isEmpty(), String.join("；", snapshot.blockers()));
		require(Objects.equals(snapshot.fingerprint(), command.getExpectedEvidenceFingerprint().trim()),
				"派工事实已变化，请刷新退派预览后重新提交");
		require(activeRequest(command.getArrangeId(), factory, true) == null, "该派工已有待审核退派申请");
		require(correctionMapper.pending(snapshot.arrange().getTenantId(), factory, command.getArrangeId()) == 0,
				"该派工已有待审核修正申请");

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
		request.setTenantId(TenantContextHolder.getTenantId());
		require(baseMapper.insert(request) == 1, "退派申请保存失败");
		saveOperation(request, requestId, fingerprint, "SUBMIT", null, "SUBMITTED",
				command.getReason().trim(), user.getId(), user.getUsername(), user.getName(), now);
		return request;
	}

	/** {@inheritDoc} */
	@Override
	@Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
	public MesDispatchReturnRequestEntity review(MesDispatchReturnDTO.ReviewCommand command) {
		String factory = factoryAccess.require("write");
		validateReview(command);
		MomUser user = actor();
		String operationRequestId = command.getOperationRequestId().trim();
		String operationFingerprint = reviewFingerprint(command, user);
		baseMapper.lockKey("COMMAND:" + operationRequestId);
		MesDispatchReturnDTO.Receipt replay = originalReceipt("REVIEW", operationRequestId,
				operationFingerprint, command.getRequestId(), null, command.getApproved(), factory, user);
		if ("APPLIED".equals(replay.getStatus())) {
			return replay.getRequest();
		}

		MesDispatchReturnRequestEntity owner = request(command.getRequestId(), factory, false);
		bindingGroups.lockDispatchTask(owner.getTaskCode());
		MesDispatchReturnRequestEntity request = request(command.getRequestId(), factory, true);
		taskLock.assertTaskCode(owner.getTaskCode(), request.getTaskCode());
		require("SUBMITTED".equals(request.getStatus()), "只有待审核退派申请可以审批");
		require(!Objects.equals(request.getApplicantId(), user.getId())
						&& !Objects.equals(request.getApplicantCode(), user.getUsername()),
				"退派申请人与审批人必须分离");
		// 即使驳回也先识别原证据版本；未知版本不能静默按新旧合同处理。
		String version = evidenceVersion(request);
		require(Objects.equals(request.getEvidenceFingerprint(), command.getExpectedEvidenceFingerprint().trim()),
				"审批页面证据与原申请不一致，请刷新");
		LocalDateTime now = LocalDateTime.now();
		String before = request.getStatus();
		request.setReviewRequestId(operationRequestId).setReviewFingerprint(operationFingerprint)
				.setApproved(command.getApproved()).setReviewReason(command.getReason().trim())
				.setReviewerId(user.getId()).setReviewerCode(user.getUsername()).setReviewerName(user.getName())
				.setReviewedTime(now);

		if (!Boolean.TRUE.equals(command.getApproved())) {
			request.setStatus("REJECTED").setHandledTime(now).setResultMessage("退派申请已驳回");
			updateRequest(request, "退派驳回结果保存失败");
			saveOperation(request, operationRequestId, operationFingerprint, "REJECT", before, "REJECTED",
					command.getReason().trim(), user.getId(), user.getUsername(), user.getName(), now);
			return request;
		}

		baseMapper.lockKey("ARRANGE:" + request.getArrangeId());
		EvidenceSnapshot current = evidence(request.getArrangeId(), factory, true, version);
		require(Objects.equals(current.arrange().getTaskCode(), request.getTaskCode())
				&& Objects.equals(current.arrange().getProduceTaskCode(), request.getProduceTaskCode())
				&& Objects.equals(current.arrange().getCode(), request.getArrangeCode()), "原申请的真实派工归属已变化");
		terminalExecutionGuard.assertUnoccupied(current.arrange());
		if (!current.blockers().isEmpty() || !Objects.equals(request.getEvidenceFingerprint(), current.fingerprint())) {
			request.setStatus("STALE").setApproved(Boolean.FALSE).setHandledTime(now)
					.setResultMessage(current.blockers().isEmpty()
							? "派工、资源或现场事实已变化，原退派申请失效"
							: String.join("；", current.blockers()));
			updateRequest(request, "退派失效结果保存失败");
			saveOperation(request, operationRequestId, operationFingerprint, "MARK_STALE", before, "STALE",
					request.getResultMessage(), user.getId(), user.getUsername(), user.getName(), now);
			return request;
		}

		Map<String, Object> result = executeReturn(current, now);
		request.setStatus("APPROVED").setHandledTime(now).setResultSnapshot(JSONUtil.toJsonStr(result))
				.setResultMessage(current.quotaContext() == null
						? "原派工已关闭，未投产资源已释放，工序已恢复待派工"
						: "目标派工已关闭，自身配额与未用工装已释放，父任务按实际执行状态保留");
		updateRequest(request, "退派批准结果保存失败");
		saveOperation(request, operationRequestId, operationFingerprint, "APPROVE", before, "APPROVED",
				command.getReason().trim(), user.getId(), user.getUsername(), user.getName(), now);
		return request;
	}

	/** {@inheritDoc} */
	@Override
	@Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
	public MesDispatchReturnRequestEntity withdraw(MesDispatchReturnDTO.WithdrawCommand command) {
		String factory = factoryAccess.require("write");
		validateWithdraw(command);
		MomUser user = actor();
		String commandFingerprint = withdrawFingerprint(command, user);
		baseMapper.lockKey("COMMAND:" + command.getOperationRequestId().trim());
		MesDispatchReturnDTO.Receipt replay = originalReceipt("WITHDRAW", command.getOperationRequestId().trim(),
				commandFingerprint, command.getRequestId(), null, null, factory, user);
		if ("APPLIED".equals(replay.getStatus())) {
			return replay.getRequest();
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
		updateRequest(request, "退派申请撤回失败");
		saveOperation(request, command.getOperationRequestId().trim(), commandFingerprint, "WITHDRAW",
				"SUBMITTED", "WITHDRAWN", command.getReason().trim(), user.getId(), user.getUsername(),
				user.getName(), now);
		return request;
	}

	/** {@inheritDoc} */
	@Override
	@Transactional(readOnly = true)
	public MesDispatchReturnDTO.Receipt submitReceipt(MesDispatchReturnDTO.SubmitCommand command) {
		String factory = factoryAccess.require("read");
		validateSubmit(command);
		MomUser user = actor();
		return originalReceipt("SUBMIT", command.getRequestId().trim(), submitFingerprint(command, user),
				null, command.getArrangeId(), null, factory, user);
	}

	/** {@inheritDoc} */
	@Override
	@Transactional(readOnly = true)
	public MesDispatchReturnDTO.Receipt reviewReceipt(MesDispatchReturnDTO.ReviewCommand command) {
		String factory = factoryAccess.require("read");
		validateReview(command);
		MomUser user = actor();
		return originalReceipt("REVIEW", command.getOperationRequestId().trim(), reviewFingerprint(command, user),
				command.getRequestId(), null, command.getApproved(), factory, user);
	}

	/** {@inheritDoc} */
	@Override
	@Transactional(readOnly = true)
	public MesDispatchReturnDTO.Receipt withdrawReceipt(MesDispatchReturnDTO.WithdrawCommand command) {
		String factory = factoryAccess.require("read");
		validateWithdraw(command);
		MomUser user = actor();
		return originalReceipt("WITHDRAW", command.getOperationRequestId().trim(), withdrawFingerprint(command, user),
				command.getRequestId(), null, null, factory, user);
	}

	/**
	 * 只按完整原命令核永久操作，不读取当前证据、不申请根写锁、不穿透后来的hold。
	 * 指纹保持既有算法，另核原人主键、工号、操作类型和实际组织，避免同工号换人重放。
	 * 查无永久记录仅表示本次读未见提交，调用方须保留原号原文继续恢复。
	 */
	private MesDispatchReturnDTO.Receipt originalReceipt(String kind, String requestId, String fingerprint,
			Long returnRequestId, Long arrangeId, Boolean approved,
			String factory, MomUser user) {
		Long tenant = TenantContextHolder.getTenantId();
		MesDispatchReturnOperationEntity operation = operationMapper.selectOne(
				new LambdaQueryWrapper<MesDispatchReturnOperationEntity>()
						.eq(MesDispatchReturnOperationEntity::getTenantId, tenant)
						.eq(MesDispatchReturnOperationEntity::getFactoryCode, factory)
						.eq(MesDispatchReturnOperationEntity::getRequestId, requestId), false);
		MesDispatchReturnRequestEntity submitted = getOne(new LambdaQueryWrapper<MesDispatchReturnRequestEntity>()
				.eq(MesDispatchReturnRequestEntity::getTenantId, tenant)
				.eq(MesDispatchReturnRequestEntity::getFactoryCode, factory)
				.eq(MesDispatchReturnRequestEntity::getSubmitRequestId, requestId), false);
		if (operation == null) {
			require(submitted == null, "原提交缺少永久操作履历，不能认定未执行或生成第二次命令");
			if (returnRequestId != null) {
				request(returnRequestId, factory, false);
			} else {
				require(arrangeMapper.selectCount(new LambdaQueryWrapper<MesTaskArrangeEntity>()
						.eq(MesTaskArrangeEntity::getId, arrangeId)
						.eq(MesTaskArrangeEntity::getTenantId, tenant)
						.eq(MesTaskArrangeEntity::getFactoryCode, factory)) == 1,
						"原命令派工不属于当前实际租户和工厂");
			}
			return new MesDispatchReturnDTO.Receipt().setStatus("ABSENT").setKind(kind).setRequestId(requestId);
		}

		require(Objects.equals(operation.getTenantId(), tenant) && Objects.equals(operation.getFactoryCode(), factory),
				"原永久操作不属于当前实际组织");
		require(Objects.equals(operation.getOperatorId(), user.getId())
				&& Objects.equals(operation.getOperatorCode(), user.getUsername()), "必须由原实名操作人核对或重放原命令");
		require(Objects.equals(operation.getRequestFingerprint(), fingerprint), "永久请求号已被不同命令使用");
		boolean typeMatches = switch (kind) {
			case "SUBMIT" -> "SUBMIT".equals(operation.getOperationType());
			case "WITHDRAW" -> "WITHDRAW".equals(operation.getOperationType());
			case "REVIEW" -> Boolean.TRUE.equals(approved)
					? Set.of("APPROVE", "MARK_STALE").contains(operation.getOperationType())
					: "REJECT".equals(operation.getOperationType());
			default -> false;
		};
		require(typeMatches, "永久请求号已用于其他退派操作类型");
		MesDispatchReturnRequestEntity current = request(operation.getReturnRequestId(), factory, false);
		if ("SUBMIT".equals(kind)) {
			require(submitted != null && Objects.equals(submitted.getId(), current.getId())
					&& Objects.equals(current.getSubmitRequestId(), requestId)
					&& Objects.equals(current.getSubmitFingerprint(), fingerprint)
					&& Objects.equals(current.getArrangeId(), arrangeId)
					&& Objects.equals(current.getApplicantId(), user.getId())
					&& Objects.equals(current.getApplicantCode(), user.getUsername()), "原提交身份、派工或永久回执不一致");
		} else {
			require(submitted == null && Objects.equals(current.getId(), returnRequestId), "原永久操作不属于该退派申请");
			if ("WITHDRAW".equals(kind)) {
				require(Objects.equals(current.getApplicantId(), user.getId())
						&& Objects.equals(current.getApplicantCode(), user.getUsername()), "撤回回执必须属于原申请人");
			}
		}
		return new MesDispatchReturnDTO.Receipt().setStatus("APPLIED").setKind(kind).setRequestId(requestId)
				.setOperation(operation).setRequest(current);
	}

	/**
	 * 保持原提交命令的规范化及哈希顺序，历史永久请求号不得因本轮升级重算指纹。
	 */
	private String submitFingerprint(MesDispatchReturnDTO.SubmitCommand command, MomUser user) {
		return hash(List.of(command.getArrangeId(), command.getExpectedEvidenceFingerprint().trim(),
				command.getReason().trim(), user.getUsername()));
	}

	/**
	 * 保持原审批哈希合同；批准变为失效时仍以原批准原文回读MARK_STALE操作。
	 */
	private String reviewFingerprint(MesDispatchReturnDTO.ReviewCommand command, MomUser user) {
		return hash(List.of(command.getRequestId(), command.getApproved(), command.getExpectedEvidenceFingerprint(),
				command.getReason().trim(), user.getUsername()));
	}

	/**
	 * 保持原撤回命令指纹，不把当前原因或申请当前状态混入旧回执。
	 */
	private String withdrawFingerprint(MesDispatchReturnDTO.WithdrawCommand command, MomUser user) {
		return hash(List.of(command.getRequestId(), command.getReason().trim(), user.getUsername()));
	}

	/** {@inheritDoc} */
	@Override
	@Transactional(readOnly = true)
	public void assertExecutionAllowed(MesTaskArrangeEntity arrange) {
		if (arrange == null || arrange.getId() == null) {
			return;
		}
		require(correctionMapper.pending(arrange.getTenantId(), arrange.getFactoryCode(), arrange.getId()) == 0,
				"派工存在待审核修正申请，不能接收或开工");
		MesDispatchReturnRequestEntity pending = activeRequest(arrange.getId(), arrange.getFactoryCode(), false);
		if (pending != null) {
			throw new CheckedException("派工存在待审核退派申请[" + pending.getRequestNo() + "]，不能开工");
		}
	}

	/** {@inheritDoc} */
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
		String factory = factoryAccess.require("write");
		require(arranges.stream().allMatch(arrange -> Objects.equals(arrange.getTenantId(), TenantContextHolder.getTenantId())
				&& Objects.equals(arrange.getFactoryCode(), factory)), "计划接管派工必须属于当前实际租户和工厂");
		List<MesDispatchReturnRequestEntity> pending = list(new LambdaQueryWrapper<MesDispatchReturnRequestEntity>()
				.eq(MesDispatchReturnRequestEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(MesDispatchReturnRequestEntity::getFactoryCode, factory)
				.in(MesDispatchReturnRequestEntity::getArrangeId, arrangeIds)
				.eq(MesDispatchReturnRequestEntity::getStatus, "SUBMITTED")
				.orderByAsc(MesDispatchReturnRequestEntity::getId).last("FOR UPDATE"));
		LocalDateTime now = LocalDateTime.now();
		for (MesDispatchReturnRequestEntity request : pending) {
			String operationRequestId = "PLAN-SUP-" + hash(planRequestId + '|' + request.getId()).substring(0, 40);
			String operationFingerprint = hash(List.of(request.getId(), planRequestId, "SUPERSEDED"));
			request.setStatus("SUPERSEDED").setHandledTime(now)
					.setResultMessage("上游已审批计划撤回接管，现场退派申请终止");
			updateRequest(request, "计划撤回接管现场退派申请失败");
			saveOperation(request, operationRequestId, operationFingerprint, "SUPERSEDE_BY_PLAN",
					"SUBMITTED", "SUPERSEDED", request.getResultMessage(), null, "SYSTEM", "系统", now);
		}
	}

	/** {@inheritDoc} */
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
		EvidenceSnapshot current = evidence(correction.getArrangeId(), factory, true, LEGACY_EVIDENCE_VERSION);
		terminalExecutionGuard.assertUnoccupied(current.arrange());
		require(current.blockers().isEmpty(), String.join("；", current.blockers()));
		require(Objects.equals(current.fingerprint(), correction.getSourceFingerprint())
						&& Objects.equals(current.arrange().getTaskCode(), correction.getTaskCode())
						&& Objects.equals(current.arrange().getProduceTaskCode(), correction.getProduceTaskCode()),
				"原修正派工事实已变化");
		require(activeRequest(correction.getArrangeId(), factory, true) == null, "存在另一待审退派申请");
		require(correctionMapper.workpieceFacts(tenant, factory, correction.getArrangeId()) == 0, "原派工已有工件时间事实");
		executeReturn(current, LocalDateTime.now());
	}

	/**
	 * 只关闭已重新核定的目标并释放其未用工装。新版在同事务核400结算结果，
	 * 不把兄弟推动的父在制/暂停状态回退；历史V1继续使用原未执行整根回退规则。
	 */
	private Map<String, Object> executeReturn(EvidenceSnapshot snapshot, LocalDateTime handledTime) {
		MesTaskArrangeEntity arrange = snapshot.arrange();
		Long tenant = arrange.getTenantId();
		String factory = arrange.getFactoryCode();
		List<String> releasedToolings = new ArrayList<>();
		for (MesToolingExecutionEntity tooling : snapshot.toolings()) {
			if ("CHECKED_IN".equals(tooling.getStatus())) {
				require(toolingExecutionMapper.update(null, new LambdaUpdateWrapper<MesToolingExecutionEntity>()
						.eq(MesToolingExecutionEntity::getId, tooling.getId())
						.eq(MesToolingExecutionEntity::getTenantId, tenant)
						.eq(MesToolingExecutionEntity::getFactoryCode, factory)
						.eq(MesToolingExecutionEntity::getArrangeCode, arrange.getCode())
						.eq(MesToolingExecutionEntity::getStatus, "CHECKED_IN")
						.set(MesToolingExecutionEntity::getStatus, "RELEASED")
						.set(MesToolingExecutionEntity::getReleaseTime, handledTime)) == 1,
						"工装[" + tooling.getToolingCode() + "]释放失败");
				releasedToolings.add(tooling.getToolingCode());
			}
		}

		require(arrangeMapper.update(null, new LambdaUpdateWrapper<MesTaskArrangeEntity>()
				.eq(MesTaskArrangeEntity::getId, arrange.getId())
				.eq(MesTaskArrangeEntity::getTenantId, tenant)
				.eq(MesTaskArrangeEntity::getFactoryCode, factory)
				.eq(MesTaskArrangeEntity::getTaskCode, arrange.getTaskCode())
				.eq(MesTaskArrangeEntity::getProduceTaskCode, arrange.getProduceTaskCode())
				.eq(MesTaskArrangeEntity::getStatus, arrange.getStatus())
				.set(MesTaskArrangeEntity::getStatus, MesProduceTaskStatusEnum.CLOSE.name())) == 1,
				"原派工关闭失败");
		arrange.setStatus(MesProduceTaskStatusEnum.CLOSE.name());
		MesDispatchReturnDTO.AllocationEvidence allocation = snapshot.quotaContext() == null ? null
				: quotaReturnSupport.settleAndRead(snapshot.quotaContext(), arrange);

		long otherActiveForProduce = arrangeMapper.selectCount(new LambdaQueryWrapper<MesTaskArrangeEntity>()
				.eq(MesTaskArrangeEntity::getTenantId, tenant)
				.eq(MesTaskArrangeEntity::getFactoryCode, factory)
				.eq(MesTaskArrangeEntity::getTaskCode, arrange.getTaskCode())
				.eq(MesTaskArrangeEntity::getProduceTaskCode, arrange.getProduceTaskCode())
				.ne(MesTaskArrangeEntity::getId, arrange.getId())
				.in(MesTaskArrangeEntity::getStatus, ACTIVE_ARRANGE_STATUSES));
		MesProduceTaskEntity produceTask = snapshot.produceTask();
		String nextProduceStatus = produceTask.getStatus();
		boolean legacy = snapshot.quotaContext() == null;
		if (otherActiveForProduce == 0 && (legacy || pristineProduce(produceTask, tenant, factory))) {
			require(produceTaskMapper.update(null, new LambdaUpdateWrapper<MesProduceTaskEntity>()
					.eq(MesProduceTaskEntity::getId, produceTask.getId())
					.eq(MesProduceTaskEntity::getTenantId, tenant)
					.eq(MesProduceTaskEntity::getFactoryCode, factory)
					.eq(MesProduceTaskEntity::getTaskCode, arrange.getTaskCode())
					.eq(MesProduceTaskEntity::getStatus, produceTask.getStatus())
					.set(MesProduceTaskEntity::getStatus, MesProduceTaskStatusEnum.WAIT.name())) == 1,
					"工序任务恢复待派工失败");
			nextProduceStatus = MesProduceTaskStatusEnum.WAIT.name();
		}

		long otherActiveForTask = arrangeMapper.selectCount(new LambdaQueryWrapper<MesTaskArrangeEntity>()
				.eq(MesTaskArrangeEntity::getTenantId, tenant)
				.eq(MesTaskArrangeEntity::getFactoryCode, factory)
				.eq(MesTaskArrangeEntity::getTaskCode, arrange.getTaskCode())
				.ne(MesTaskArrangeEntity::getId, arrange.getId())
				.in(MesTaskArrangeEntity::getStatus, ACTIVE_ARRANGE_STATUSES));
		MesTaskEntity task = snapshot.task();
		String nextTaskStatus = task.getStatus();
		if (legacy || otherActiveForTask == 0 && pristineTask(task, tenant, factory)) {
			nextTaskStatus = otherActiveForTask == 0
					? MesTaskStatusEnum.ISSUE.name() : MesTaskStatusEnum.DISPATCH.name();
		}
		if (!Objects.equals(task.getStatus(), nextTaskStatus)) {
			require(taskMapper.update(null, new LambdaUpdateWrapper<MesTaskEntity>()
					.eq(MesTaskEntity::getId, task.getId())
					.eq(MesTaskEntity::getTenantId, tenant)
					.eq(MesTaskEntity::getFactoryCode, factory)
					.eq(MesTaskEntity::getStatus, task.getStatus())
					.set(MesTaskEntity::getStatus, nextTaskStatus)) == 1, "生产工单状态回退失败");
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
		if (allocation != null) {
			result.put("allocation", allocationFacts(allocation));
		}
		return result;
	}

	/**
	 * 新配额原件中的ID和数量显式用十进制文本，Hutool存文不依赖Jackson序列化注解。
	 */
	private Map<String, Object> allocationFacts(MesDispatchReturnDTO.AllocationEvidence allocation) {
		Map<String, Object> facts = new LinkedHashMap<>();
		facts.put("id", allocation.getId() == null ? null : allocation.getId().toString());
		facts.put("allocationKey", allocation.getAllocationKey());
		facts.put("allocatedQuantity", allocation.getAllocatedQuantity() == null
				? null : decimal(allocation.getAllocatedQuantity()));
		facts.put("occupiedQuantity", allocation.getOccupiedQuantity() == null
				? null : decimal(allocation.getOccupiedQuantity()));
		facts.put("settlementStatus", allocation.getSettlementStatus());
		return facts;
	}

	/**
	 * 新版只在原父工序未执行且数量、时间和原始记录均明确为零时恢复待派工。
	 * PRODUCT/PAUSE不进入此分支，NULL及负数也不能当作零。
	 */
	private boolean pristineProduce(MesProduceTaskEntity task, Long tenant, String factory) {
		return Set.of("WAIT", "UN_RECEIVE", "RECEIVE").contains(Objects.toString(task.getStatus(), ""))
				&& task.getStartTime() == null && task.getFinishTime() == null
				&& zero(task.getCompleteNum()) && zero(task.getQualityReleasedNum()) && zero(task.getUnPassNum())
				&& parentRecordsEmpty(tenant, factory, task.getTaskCode(), task.getCode());
	}

	/**
	 * 根工单仅在原下达/已派工、严格零事实且无活动兄弟时恢复ISSUE；在制根永不回退。
	 */
	private boolean pristineTask(MesTaskEntity task, Long tenant, String factory) {
		return Set.of("ISSUE", "DISPATCH").contains(Objects.toString(task.getStatus(), ""))
				&& task.getStartTime() == null && task.getFinishTime() == null
				&& zero(task.getCompleteNum()) && zero(task.getInStockNum())
				&& zero(task.getUnInStockNum()) && zero(task.getUnPassNum())
				&& parentRecordsEmpty(tenant, factory, task.getCode(), null);
	}

	/**
	 * 父状态回退额外核全部历史子派工和执行记录，避免仅凭累计量为零覆盖冲销前执行历史。
	 */
	private boolean parentRecordsEmpty(Long tenant, String factory, String taskCode, String produceTaskCode) {
		List<MesTaskArrangeEntity> children = arrangeMapper.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
				.eq(MesTaskArrangeEntity::getTenantId, tenant)
				.eq(MesTaskArrangeEntity::getFactoryCode, factory)
				.eq(MesTaskArrangeEntity::getTaskCode, taskCode)
				.eq(produceTaskCode != null, MesTaskArrangeEntity::getProduceTaskCode, produceTaskCode));
		if (children.isEmpty() || children.stream().anyMatch(child -> child.getStartTime() != null
				|| child.getFinishTime() != null || !zero(child.getCompleteNum())
				|| !zero(child.getQualityReleasedNum()) || !zero(child.getUnPassNum())
				|| !Boolean.TRUE.equals(quotaReturnMapper.noExecution(tenant, factory, child.getId())))) {
			return false;
		}
		return reportMapper.selectCount(new LambdaQueryWrapper<MesTaskReportEntity>()
				.eq(MesTaskReportEntity::getTenantId, tenant)
				.eq(MesTaskReportEntity::getFactoryCode, factory)
				.eq(MesTaskReportEntity::getTaskCode, taskCode)
				.eq(produceTaskCode != null, MesTaskReportEntity::getProduceTaskCode, produceTaskCode)) == 0
				&& feedMapper.selectCount(new LambdaQueryWrapper<MesWarehouseFeedEntity>()
				.eq(MesWarehouseFeedEntity::getTenantId, tenant)
				.eq(MesWarehouseFeedEntity::getFactoryCode, factory)
				.eq(MesWarehouseFeedEntity::getTaskCode, taskCode)
				.eq(produceTaskCode != null, MesWarehouseFeedEntity::getProduceTaskCode, produceTaskCode)) == 0
				&& executionSegmentMapper.selectCount(new LambdaQueryWrapper<MesTaskExecutionSegmentEntity>()
				.eq(MesTaskExecutionSegmentEntity::getTenantId, tenant)
				.eq(MesTaskExecutionSegmentEntity::getFactoryCode, factory)
				.eq(MesTaskExecutionSegmentEntity::getTaskCode, taskCode)
				.eq(produceTaskCode != null, MesTaskExecutionSegmentEntity::getProduceTaskCode, produceTaskCode)) == 0;
	}

	/**
	 * 先取当前真实父子和现场事实，再按AUTO或原快照版本取证；锁流程已先取得真实ROOT锁。
	 * V1的facts顺序、序列化值及二次JSON哈希保持原协议，新增字段只进入新版证据。
	 */
	private EvidenceSnapshot evidence(Long arrangeId, String factory, boolean lock, String version) {
		require(arrangeId != null, "请选择派工任务");
		require(AUTO_EVIDENCE_VERSION.equals(version) || LEGACY_EVIDENCE_VERSION.equals(version)
				|| MesDispatchQuotaReturnSupport.EVIDENCE_VERSION.equals(version), "不支持的退派证据版本");
		Long tenantId = TenantContextHolder.getTenantId();
		MesTaskArrangeEntity arrange = arrangeMapper.selectOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
				.eq(MesTaskArrangeEntity::getId, arrangeId)
				.eq(MesTaskArrangeEntity::getTenantId, tenantId)
				.eq(MesTaskArrangeEntity::getFactoryCode, factory)
				.last(lock ? "FOR UPDATE" : ""));
		require(arrange != null, "当前租户和工厂下不存在该派工任务");
		MesTaskEntity task = taskMapper.selectOne(new LambdaQueryWrapper<MesTaskEntity>()
				.eq(MesTaskEntity::getCode, arrange.getTaskCode())
				.eq(MesTaskEntity::getTenantId, tenantId)
				.eq(MesTaskEntity::getFactoryCode, factory)
				.last(lock ? "FOR UPDATE" : ""));
		MesProduceTaskEntity produceTask = produceTaskMapper.selectOne(new LambdaQueryWrapper<MesProduceTaskEntity>()
				.eq(MesProduceTaskEntity::getCode, arrange.getProduceTaskCode())
				.eq(MesProduceTaskEntity::getTenantId, tenantId)
				.eq(MesProduceTaskEntity::getFactoryCode, factory)
				.last(lock ? "FOR UPDATE" : ""));
		require(task != null && produceTask != null, "派工关联的生产工单或工序任务不存在");
		require(Objects.equals(task.getCode(), produceTask.getTaskCode()), "派工、工序和生产工单归属不一致");

		long reportCount = reportMapper.selectCount(new LambdaQueryWrapper<MesTaskReportEntity>()
				.eq(MesTaskReportEntity::getTenantId, tenantId)
				.eq(MesTaskReportEntity::getFactoryCode, factory)
				.eq(MesTaskReportEntity::getArrangeCode, arrange.getCode()));
		long feedCount = feedMapper.selectCount(new LambdaQueryWrapper<MesWarehouseFeedEntity>()
				.eq(MesWarehouseFeedEntity::getTenantId, tenantId)
				.eq(MesWarehouseFeedEntity::getFactoryCode, factory)
				.eq(MesWarehouseFeedEntity::getArrangeCode, arrange.getCode()));
		long segmentCount = executionSegmentMapper.selectCount(new LambdaQueryWrapper<MesTaskExecutionSegmentEntity>()
				.eq(MesTaskExecutionSegmentEntity::getTenantId, tenantId)
				.eq(MesTaskExecutionSegmentEntity::getFactoryCode, factory)
				.eq(MesTaskExecutionSegmentEntity::getArrangeId, arrange.getId()));
		List<MesToolingExecutionEntity> toolings = toolingExecutionMapper.selectList(
				new LambdaQueryWrapper<MesToolingExecutionEntity>()
						.eq(MesToolingExecutionEntity::getTenantId, tenantId)
						.eq(MesToolingExecutionEntity::getFactoryCode, factory)
						.eq(MesToolingExecutionEntity::getArrangeCode, arrange.getCode())
						.orderByAsc(MesToolingExecutionEntity::getToolingCode)
						.orderByAsc(MesToolingExecutionEntity::getId)
						.last(lock ? "FOR UPDATE" : ""));
		long activeChangeoverCount = 0;
		if (StrUtil.isNotBlank(arrange.getPlannedDeviceCode()) && StrUtil.isNotBlank(arrange.getActualWorksiteCode())) {
			activeChangeoverCount = changeoverExecutionMapper.selectCount(
					new LambdaQueryWrapper<MesChangeoverExecutionEntity>()
							.eq(MesChangeoverExecutionEntity::getTenantId, tenantId)
							.eq(MesChangeoverExecutionEntity::getFactoryCode, factory)
							.eq(MesChangeoverExecutionEntity::getDeviceCode, arrange.getPlannedDeviceCode())
							.eq(MesChangeoverExecutionEntity::getWorksiteCode, arrange.getActualWorksiteCode())
							.eq(MesChangeoverExecutionEntity::getStatus, "STARTED"));
		}

		MesDispatchQuotaReturnSupport.QuotaContext quotaContext = LEGACY_EVIDENCE_VERSION.equals(version) ? null
				: quotaReturnSupport.inspect(arrange, task, produceTask, factory);
		require(!MesDispatchQuotaReturnSupport.EVIDENCE_VERSION.equals(version) || quotaContext != null,
				"原申请的配额证据已无法重采，不允许降级为历史整根证据");
		if (quotaContext != null) {
			return quotaEvidence(arrange, task, produceTask, toolings, reportCount, feedCount,
					segmentCount, activeChangeoverCount, quotaContext);
		}
		List<String> blockers = blockers(arrange, task, produceTask, reportCount, feedCount,
				segmentCount, toolings, activeChangeoverCount, false);
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
		return new EvidenceSnapshot(arrange, task, produceTask, toolings, blockers, hash(json), json,
				LEGACY_EVIDENCE_VERSION, null);
	}

	/**
	 * 按原申请快照选择协议并核原文完整性。旧申请不自动升级，未知版本或坏原件直接拒绝。
	 */
	private String evidenceVersion(MesDispatchReturnRequestEntity request) {
		require(StrUtil.isNotBlank(request.getEvidenceSnapshot())
				&& Objects.equals(hash(request.getEvidenceSnapshot()), request.getEvidenceFingerprint()),
				"原退派证据原文或指纹不完整，不能重新解释");
		String version;
		try {
			version = JSONUtil.parseObj(request.getEvidenceSnapshot()).getStr("version");
		} catch (RuntimeException failure) {
			throw new CheckedException("原退派证据版本无法解析", failure);
		}
		require(LEGACY_EVIDENCE_VERSION.equals(version) || MesDispatchQuotaReturnSupport.EVIDENCE_VERSION.equals(version),
				"原退派证据版本不受支持，不能自动升级或降级");
		return version;
	}

	/**
	 * 新证据只冻结目标、精确配额、资源及稳定工程身份；父累计/状态/时间只作实时门禁，
	 * 兄弟报工推进父工序不使本目标原申请仅因统计变化失效。
	 */
	private EvidenceSnapshot quotaEvidence(MesTaskArrangeEntity arrange, MesTaskEntity task,
			MesProduceTaskEntity produceTask, List<MesToolingExecutionEntity> toolings,
			long reportCount, long feedCount, long segmentCount,
			long activeChangeoverCount,
			MesDispatchQuotaReturnSupport.QuotaContext context) {
		List<String> blockers = blockers(arrange, task, produceTask, reportCount, feedCount,
				segmentCount, toolings, activeChangeoverCount, true);
		if (!context.noExecution()) {
			blockers.add("目标派工不满足400严格零执行事实，不能独立释放配额");
		}
		var allocation = context.allocation();
		if (!"RESERVED".equals(allocation.getSettlementStatus()) || allocation.getOccupiedQuantity() == null
				|| allocation.getAllocatedQuantity() == null
				|| allocation.getOccupiedQuantity().compareTo(allocation.getAllocatedQuantity()) != 0) {
			blockers.add("目标配额未处于完整原始占用状态，不能重复退回或猜测释放数量");
		}
		if (!Set.of("DISPATCH", "PRODUCT").contains(Objects.toString(task.getStatus(), "")) || task.getFinishTime() != null) {
			blockers.add("原生产工单不处于允许独立退派的非终态，当前状态=" + task.getStatus());
		}
		if (!Set.of("UN_RECEIVE", "RECEIVE", "PRODUCT", "PAUSE").contains(Objects.toString(produceTask.getStatus(), ""))
				|| produceTask.getFinishTime() != null) {
			blockers.add("原工序任务不处于允许独立退派的非终态，当前状态=" + produceTask.getStatus());
		}
		if (revisionHolds.held(task.getCode())) {
			blockers.add("生产工单正在进行质检修订核验，须等待完成或正式取消");
		}

		Map<String, Object> facts = new LinkedHashMap<>();
		facts.put("version", MesDispatchQuotaReturnSupport.EVIDENCE_VERSION);
		facts.put("tenantId", arrange.getTenantId());
		facts.put("factoryCode", arrange.getFactoryCode());
		facts.put("identity", context.identityFacts());
		facts.put("arrange", arrangeFacts(arrange));
		facts.put("noticeQuantity", decimal(arrange.getNoticeNum()));
		facts.put("plannedQuantity", decimal(arrange.getPlannedNum()));
		facts.put("targetResources", Arrays.asList(arrange.getPlannedWorkerCode(),
				arrange.getPlannedResourceGroupCode(), arrange.getWorkshopCode(),
				value(arrange.getPlannedStartTime()), value(arrange.getPlannedEndTime())));
		facts.put("allocation", allocationFacts(quotaReturnSupport.toEvidence(context)));
		facts.put("reportCount", reportCount);
		facts.put("feedCount", feedCount);
		facts.put("executionSegmentCount", segmentCount);
		facts.put("activeChangeoverCount", activeChangeoverCount);
		facts.put("toolings", toolings.stream().map(this::toolingFacts).toList());
		String json = JSONUtil.toJsonStr(facts);
		return new EvidenceSnapshot(arrange, task, produceTask, toolings, blockers, hash(json), json,
				MesDispatchQuotaReturnSupport.EVIDENCE_VERSION, context);
	}

	/**
	 * 共用目标状态、现场记录和未用工装门；仅新版跳过父实绩门，另核父真实归属与非终态。
	 */
	private List<String> blockers(MesTaskArrangeEntity arrange, MesTaskEntity task,
			MesProduceTaskEntity produceTask, long reportCount, long feedCount,
			long segmentCount, List<MesToolingExecutionEntity> toolings,
			long activeChangeoverCount, boolean targetOnly) {
		List<String> blockers = new ArrayList<>();
		if (!Set.of("UN_RECEIVE", "RECEIVE").contains(Objects.toString(arrange.getStatus(), ""))) {
			blockers.add("只有未接收或已接收且未开工的派工可以申请退派，当前状态=" + arrange.getStatus());
		}
		if ("RECEIVE".equals(arrange.getStatus()) && StrUtil.isBlank(arrange.getActualWorksiteCode())) {
			blockers.add("派工缺少接收时确认的实际工位");
		}
		if (arrange.getStartTime() != null || arrange.getFinishTime() != null || !zero(arrange.getCompleteNum())
				|| !zero(arrange.getQualityReleasedNum()) || !zero(arrange.getUnPassNum())) {
			blockers.add("派工已有开工、完工、非零或未知的生产质量数量事实");
		}
		if (!targetOnly && (!Set.of("UN_RECEIVE", "RECEIVE").contains(Objects.toString(produceTask.getStatus(), ""))
				|| produceTask.getStartTime() != null || produceTask.getFinishTime() != null
				|| !zero(produceTask.getCompleteNum()) || !zero(produceTask.getQualityReleasedNum())
				|| !zero(produceTask.getUnPassNum()))) {
			blockers.add("工序任务已有开工、完工、非零或未知的生产质量数量事实");
		}
		if (!targetOnly && !MesTaskStatusEnum.DISPATCH.name().equals(task.getStatus())) {
			blockers.add("生产工单不处于已派工状态，当前状态=" + task.getStatus());
		}
		if (!targetOnly && (task.getStartTime() != null || task.getFinishTime() != null || !zero(task.getCompleteNum())
				|| !zero(task.getInStockNum()) || !zero(task.getUnInStockNum()) || !zero(task.getUnPassNum()))) {
			blockers.add("生产工单已有开工、完工、非零或未知的生产质量及入库数量事实");
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
			if (!Set.of("CHECKED_IN", "RELEASED").contains(Objects.toString(tooling.getStatus(), ""))
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

	/** 组装当前版本证据；配额未知沿历史合同返回null，不把未知占用当作0。 */
	private MesDispatchReturnDTO.Evidence toEvidence(EvidenceSnapshot snapshot,
			MesDispatchReturnRequestEntity active,
			List<String> blockers) {
		return new MesDispatchReturnDTO.Evidence().setEvidenceVersion(snapshot.version())
				.setAllocation(quotaReturnSupport.toEvidence(snapshot.quotaContext()))
				.setArrange(snapshot.arrange())
				.setTaskStatus(snapshot.task().getStatus())
				.setProduceTaskStatus(snapshot.produceTask().getStatus())
				.setToolingExecutions(snapshot.toolings()).setEvidenceFingerprint(snapshot.fingerprint())
				.setEligible(blockers.isEmpty()).setBlockers(blockers).setActiveRequest(active);
	}

	/** 按原申请ID及当前实际组织精确回读；写流程需要时锁定原申请。 */
	private MesDispatchReturnRequestEntity request(Long id, String factory, boolean lock) {
		require(id != null, "请选择退派申请");
		MesDispatchReturnRequestEntity request = getOne(new LambdaQueryWrapper<MesDispatchReturnRequestEntity>()
				.eq(MesDispatchReturnRequestEntity::getId, id)
				.eq(MesDispatchReturnRequestEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(MesDispatchReturnRequestEntity::getFactoryCode, factory)
				.last(lock ? "FOR UPDATE" : ""), false);
		require(request != null, "当前租户和工厂下不存在该退派申请");
		return request;
	}

	/** 目标待审门显式限定租户和实际工厂，不能借同码跨组织解除执行门禁。 */
	private MesDispatchReturnRequestEntity activeRequest(Long arrangeId, String factory, boolean lock) {
		return getOne(new LambdaQueryWrapper<MesDispatchReturnRequestEntity>()
				.eq(MesDispatchReturnRequestEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(MesDispatchReturnRequestEntity::getFactoryCode, factory)
				.eq(MesDispatchReturnRequestEntity::getArrangeId, arrangeId)
				.eq(MesDispatchReturnRequestEntity::getStatus, "SUBMITTED")
				.orderByDesc(MesDispatchReturnRequestEntity::getId)
				.last(lock ? "LIMIT 1 FOR UPDATE" : "LIMIT 1"), false);
	}

	/**
	 * 只允许当前组织内原待审申请发生一次状态迁移；零行或多行均回滚整个业务事务。
	 */
	private void updateRequest(MesDispatchReturnRequestEntity request, String failureMessage) {
		require(baseMapper.update(request, new LambdaUpdateWrapper<MesDispatchReturnRequestEntity>()
				.eq(MesDispatchReturnRequestEntity::getId, request.getId())
				.eq(MesDispatchReturnRequestEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(MesDispatchReturnRequestEntity::getFactoryCode, request.getFactoryCode())
				.eq(MesDispatchReturnRequestEntity::getStatus, "SUBMITTED")) == 1, failureMessage);
	}

	/** 同事务保存不可变原操作及实名、实际组织；提交、审批和回执共用该永久依据。 */
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
		operation.setTenantId(request.getTenantId());
		require(operationMapper.insert(operation) == 1, "退派操作履历保存失败");
	}

    private void validateSubmit(MesDispatchReturnDTO.SubmitCommand command) {
        require(command != null && command.getArrangeId() != null && validRequestId(command.getRequestId())
                        && validFingerprint(command.getExpectedEvidenceFingerprint()) && validReason(command.getReason()),
                "退派申请必须提供派工、永久请求号、当前事实指纹和1至500字原因");
    }

	/**
	 * 撤回及其只读回执使用相同完整原文校验，不接受只传请求号的核对。
	 */
	private void validateWithdraw(MesDispatchReturnDTO.WithdrawCommand command) {
		require(command != null && command.getRequestId() != null
						&& validRequestId(command.getOperationRequestId()) && validReason(command.getReason()),
				"撤回退派申请必须提供申请、永久请求号和1至500字原因");
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

	/** 严格明确零，不把NULL、负数或未知数量当作未执行。 */
	private boolean zero(BigDecimal value) {
		return value != null && value.signum() == 0;
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

	/** 一次取证的内部载体；version决定原合同，quotaContext仅用于新版目标配额结算。 */
	private record EvidenceSnapshot(MesTaskArrangeEntity arrange, MesTaskEntity task,
			MesProduceTaskEntity produceTask, List<MesToolingExecutionEntity> toolings,
			List<String> blockers, String fingerprint, String json, String version,
			MesDispatchQuotaReturnSupport.QuotaContext quotaContext) {
	}
}
