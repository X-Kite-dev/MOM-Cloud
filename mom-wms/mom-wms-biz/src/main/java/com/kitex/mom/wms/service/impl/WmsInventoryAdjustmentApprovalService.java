package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.dto.WmsInventoryAdjustmentApprovalReview;
import com.kitex.mom.wms.dto.WmsInventoryAdjustmentApprovalSubmit;
import com.kitex.mom.wms.dto.WmsInventoryAdjustmentCommand;
import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;
import com.kitex.mom.wms.entity.WmsInventoryAdjustmentApprovalEntity;
import com.kitex.mom.wms.mapper.WmsInventoryAdjustmentApprovalMapper;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import com.kitex.mom.wms.service.inventoryapproval.WmsInventoryApprovalExecutionContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.factory;
import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.number;
import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;

/**
 * 每次差异申请保留独立记录；调账只能使用最新已批准申请冻结的物料属性。
 */
@Service
@RequiredArgsConstructor
public class WmsInventoryAdjustmentApprovalService {
	private static final String PENDING = "PENDING";
	private static final String APPROVED = "APPROVED";
	private static final String REJECTED = "REJECTED";
	private static final String REVOKED = "REVOKED";
	private static final String POSTED = "POSTED";

	private final WmsInventoryPlanAccess access;
	private final WmsInventoryAdjustmentApprovalMapper mapper;
	private final WmsWarehouseBusinessGuard warehouseGuard;
	private final WmsInventoryVarianceGate varianceGate;
	private final WmsInventoryGainAttributes gainAttributes;
	private final WmsInventoryLossSelectionService lossSelection;

	@Transactional(rollbackFor = Exception.class)
	public WmsInventoryAdjustmentApprovalEntity submit(WmsInventoryAdjustmentApprovalSubmit command) {
		return submit(command, false);
	}

	@Transactional(rollbackFor = Exception.class)
	public WmsInventoryAdjustmentApprovalEntity submitMobile(WmsInventoryAdjustmentApprovalSubmit command) {
		return submit(command, true);
	}

	private WmsInventoryAdjustmentApprovalEntity submit(WmsInventoryAdjustmentApprovalSubmit command, boolean mobile) {
		require(command != null && command.getPlanDetailId() != null, "请选择盘点差异明细");
		String requestId = StrUtil.trim(command.getRequestId());
		// IA1为恢复协议保留域；只有已仲裁原号、设置事务意向的可信编排可以调用。
		require(requestId == null || !requestId.startsWith("IA1_")
				|| WmsInventoryApprovalExecutionContext.submitAllowed(requestId), "保留审批原号请从原号恢复入口办理");
		require(requestId == null ||
						!(requestId.startsWith("OA1_") || requestId.startsWith("OM1_") || requestId.startsWith("OE1_")),
				"外部盘亏须从专用原号入口申请");
		String reason = StrUtil.trim(command.getReason());
		require(requestId != null && requestId.matches("[A-Za-z0-9_-]{16,64}"), "审批请求号格式不正确");
		require(StrUtil.isNotBlank(reason) && reason.length() <= 500, "差异申请原因不能为空且不能超过500字");
		var row = access.detail(command.getPlanDetailId());
		var plan = access.plan(row.getPlanId(), true);
		require(Objects.equals(plan.getWarehouseCode(), row.getWarehouseCode()), "盘点明细与原计划仓库不一致");
		// 旧号兼容读取当前审批实体；IA1由编排先核实际仓来源并优先回读永久原事件，不从此实体推断旧结果。
		var priorRequest = mapper.selectOne(Wrappers.<WmsInventoryAdjustmentApprovalEntity>lambdaQuery()
				.eq(WmsInventoryAdjustmentApprovalEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(WmsInventoryAdjustmentApprovalEntity::getFactoryCode, factory())
				.eq(WmsInventoryAdjustmentApprovalEntity::getRequestId, requestId));
		if (priorRequest != null) {
			var basis = new WmsInsideInventoryPlanDetailEntity();
			basis.setMatUnit(row.getMatUnit());
			basis.setMatBatch(row.getMatBatch());
			basis.setCurrentNumber(priorRequest.getExpectedCurrentNumber());
			basis.setInventoryNum(priorRequest.getExpectedInventoryNum());
			require(Objects.equals(priorRequest.getPlanId(), plan.getId())
							&& Objects.equals(priorRequest.getWarehouseCode(), row.getWarehouseCode())
							&& Objects.equals(priorRequest.getPlanDetailId(), row.getId())
							&& number(command.getExpectedCurrentNumber(), "页面账面数量").compareTo(
							number(priorRequest.getExpectedCurrentNumber(), "原申请账面数量")) == 0
							&& number(command.getExpectedInventoryNum(), "页面实盘数量").compareTo(
							number(priorRequest.getExpectedInventoryNum(), "原申请实盘数量")) == 0
							&& WmsInventorySelectionSnapshot.matches(basis, priorRequest.getSelectedDetailJson(),
							command.getDetail(), command.getOwnershipConfirmed(), command.getLossStocks())
							&& Objects.equals(priorRequest.getReason(), reason),
					"审批请求号已用于不同内容，请核对原申请");
			return priorRequest;
		}
		require("1".equals(plan.getStatus()) && "0".equals(plan.getHandleState())
						&& "1".equals(row.getStatus()) && "0".equals(row.getPlStatus()),
				"盘点差异尚未实盘完成或已调账，不能提交审批");
		BigDecimal current = number(row.getCurrentNumber(), "账面数量");
		BigDecimal counted = number(row.getInventoryNum(), "实盘数量");
		require(current.compareTo(counted) != 0, "零差异无需提交损益审批");
		require(current.compareTo(number(command.getExpectedCurrentNumber(), "页面账面数量")) == 0
						&& counted.compareTo(number(command.getExpectedInventoryNum(), "页面实盘数量")) == 0,
				"盘点数量已变化，请刷新后重新提交审批");
		String selection = WmsInventorySelectionSnapshot.capture(row, command.getDetail(),
				command.getOwnershipConfirmed());
		require(counted.compareTo(current) < 0 || command.getLossStocks() == null || command.getLossStocks().isEmpty(),
				"盘盈不能指定盘亏实物");
		if (counted.compareTo(current) > 0) {
			gainAttributes.requireReady(row, command.getDetail());
		}
		var latest = latest(row.getId(), true);
		require(latest == null || REJECTED.equals(latest.getStatus()) || REVOKED.equals(latest.getStatus()),
				"该明细已有待审核或已通过的申请，请处理原申请");
		if (mobile) {
			warehouseGuard.checkMobile(plan.getWarehouseCode());
		}
		String variancePolicy = varianceGate.freeze(row);
		if (command.getLossStocks() != null && !command.getLossStocks().isEmpty()) {
			selection = lossSelection.freeze(row, command.getLossStocks(), selection);
		}
		String applicant = actor();
		var approval = new WmsInventoryAdjustmentApprovalEntity();
		approval.setTenantId(TenantContextHolder.getTenantId());
		approval.setFactoryCode(factory());
		approval.setPlanId(plan.getId());
		approval.setPlanDetailId(row.getId());
		approval.setWarehouseCode(plan.getWarehouseCode());
		approval.setRequestId(requestId);
		approval.setStatus(PENDING);
		approval.setExpectedCurrentNumber(current.doubleValue());
		approval.setExpectedInventoryNum(counted.doubleValue());
		approval.setSelectedDetailJson(selection);
		approval.setVariancePolicyJson(variancePolicy);
		approval.setReason(reason);
		approval.setApplicantCode(applicant);
		approval.setSubmittedTime(LocalDateTime.now());
		require(mapper.insert(approval) == 1, "盘点差异审批申请保存失败");
		return approval;
	}

	@Transactional(rollbackFor = Exception.class)
	public WmsInventoryAdjustmentApprovalEntity review(WmsInventoryAdjustmentApprovalReview command) {
		return review(command, false);
	}

	@Transactional(rollbackFor = Exception.class)
	public WmsInventoryAdjustmentApprovalEntity reviewMobile(WmsInventoryAdjustmentApprovalReview command) {
		return review(command, true);
	}

	private WmsInventoryAdjustmentApprovalEntity review(WmsInventoryAdjustmentApprovalReview command, boolean mobile) {
		require(command != null && command.getApprovalId() != null, "请选择审批申请");
		require(List.of("APPROVE", "REJECT", "REVOKE").contains(command.getDecision()), "审核结论无效");
		String comment = StrUtil.trim(command.getComment());
		require(StrUtil.isNotBlank(comment) && comment.length() <= 500, "审核意见不能为空且不能超过500字");
		var observed = approval(command.getApprovalId(), false);
		require(observed.getRequestId() == null || !observed.getRequestId().startsWith("OA1_"),
				"外部盘亏须从专用原号入口办理审核或撤销");
		// 新域申请不能从没有稳定动作号的旧审核路径绕过已结束号，历史申请保持兼容。
		require(observed.getRequestId() == null || !observed.getRequestId().startsWith("IA1_")
						|| WmsInventoryApprovalExecutionContext.reviewAllowed(command.getApprovalId(), command.getDecision()),
				"该申请请从审批原号恢复入口审核或撤销");
		var row = access.detail(observed.getPlanDetailId());
		var plan = access.plan(row.getPlanId(), true);
		var current = approval(command.getApprovalId(), true);
		boolean revoke = "REVOKE".equals(command.getDecision());
		require(Objects.equals(current.getPlanId(), plan.getId())
						&& Objects.equals(current.getWarehouseCode(), plan.getWarehouseCode())
						&& Objects.equals(latest(row.getId(), true).getId(), current.getId())
						&& (revoke ? APPROVED.equals(current.getStatus()) : PENDING.equals(current.getStatus())),
				"审批申请已处理或不属于当前盘点范围");
		require("0".equals(row.getPlStatus()), "该明细已调账，不能变更审核结论");
		if ("APPROVE".equals(command.getDecision())) {
			require("1".equals(plan.getStatus()) && "0".equals(plan.getHandleState())
							&& "0".equals(row.getPlStatus())
							&& number(row.getCurrentNumber(), "账面数量").compareTo(
							number(current.getExpectedCurrentNumber(), "申请账面数量")) == 0
							&& number(row.getInventoryNum(), "实盘数量").compareTo(
							number(current.getExpectedInventoryNum(), "申请实盘数量")) == 0,
					"审批依据已变化，请驳回并重新提交");
		}
		if ("APPROVE".equals(command.getDecision())) {
			WmsInventorySelectionSnapshot.read(row, current.getSelectedDetailJson(), true);
		}
		String reviewer = actor();
		require(!Objects.equals(reviewer, current.getApplicantCode()), "申请人与审核人必须为不同人员");
		if (mobile) {
			warehouseGuard.checkMobile(plan.getWarehouseCode());
		}
		if (revoke) {
			current.setStatus(REVOKED);
			current.setRevokedBy(reviewer);
			current.setRevokedTime(LocalDateTime.now());
			current.setRevokeReason(comment);
			require(mapper.updateById(current) == 1, "撤销盘点差异批准失败");
			return current;
		}
		current.setStatus("APPROVE".equals(command.getDecision()) ? APPROVED : REJECTED);
		current.setReviewerCode(reviewer);
		current.setReviewedTime(LocalDateTime.now());
		current.setReviewComment(comment);
		require(mapper.updateById(current) == 1, "保存盘点差异审核结论失败");
		return current;
	}

	public List<WmsInventoryAdjustmentApprovalEntity> history(Long planId) {
		var plan = access.plan(planId, false);
		return mapper.selectList(Wrappers.<WmsInventoryAdjustmentApprovalEntity>lambdaQuery()
				.eq(WmsInventoryAdjustmentApprovalEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(WmsInventoryAdjustmentApprovalEntity::getFactoryCode, factory())
				.eq(WmsInventoryAdjustmentApprovalEntity::getPlanId, plan.getId())
				.eq(WmsInventoryAdjustmentApprovalEntity::getWarehouseCode, plan.getWarehouseCode())
				.orderByDesc(WmsInventoryAdjustmentApprovalEntity::getId));
	}

	public WmsInventoryAdjustmentApprovalEntity get(Long approvalId) {
		return approval(approvalId, false);
	}

	public WmsInventoryAdjustmentApprovalEntity requireApproved(WmsInsideInventoryPlanDetailEntity row,
	                                                            WmsInventoryAdjustmentCommand command) {
		var approval = latest(row.getId(), true);
		require(approval != null && (approval.getRequestId() == null || !approval.getRequestId().startsWith("OA1_"))
						&& APPROVED.equals(approval.getStatus())
						&& Objects.equals(approval.getPlanId(), row.getPlanId())
						&& Objects.equals(approval.getWarehouseCode(), row.getWarehouseCode())
						&& number(row.getCurrentNumber(), "账面数量").compareTo(
						number(approval.getExpectedCurrentNumber(), "审批账面数量")) == 0
						&& number(row.getInventoryNum(), "实盘数量").compareTo(
						number(approval.getExpectedInventoryNum(), "审批实盘数量")) == 0
						&& WmsInventorySelectionSnapshot.matches(row, approval.getSelectedDetailJson(),
						command.getDetail(), command.getOwnershipConfirmed(), command.getLossStocks()),
				"盘点差异尚未审核通过或调账依据已变化");
		WmsInventorySelectionSnapshot.read(row, approval.getSelectedDetailJson(), true);
		return approval;
	}

	/**
	 * 原已调账复核先判定所属审批域，避免把外部v4送进旧版本解析器。
	 */
	public WmsInventoryAdjustmentApprovalEntity postedExternal(WmsInsideInventoryPlanDetailEntity row) {
		var a = latest(row.getId(), true);
		return a != null && a.getRequestId() != null && a.getRequestId().startsWith("OA1_") ? a : null;
	}

	public WmsInventorySelectionSnapshot.Frozen postedSelection(WmsInsideInventoryPlanDetailEntity row) {
		var approval = latest(row.getId(), true);
		if (approval == null) {
			return null; // 早期已记账结果只适用原回执合同，不补写新货权确认。
		}
		require(Objects.equals(approval.getPlanId(), row.getPlanId())
						&& Objects.equals(approval.getWarehouseCode(), row.getWarehouseCode())
						&& number(row.getCurrentNumber(), "账面数量").compareTo(
						number(approval.getExpectedCurrentNumber(), "审批账面数量")) == 0
						&& number(row.getInventoryNum(), "实盘数量").compareTo(
						number(approval.getExpectedInventoryNum(), "审批实盘数量")) == 0,
				"原盘点与已调账审批范围不一致，请核对");
		var frozen = WmsInventorySelectionSnapshot.read(row, approval.getSelectedDetailJson(), false);
		if (frozen.version() == 1) {
			return null;
		}
		require(POSTED.equals(approval.getStatus()), "损益已处理但审批记账状态不一致，请核对原申请");
		return frozen;
	}

	public void markPosted(WmsInventoryAdjustmentApprovalEntity approval) {
		if (approval == null) {
			return;
		}
		require(APPROVED.equals(approval.getStatus()), "盘点审批状态已变化");
		approval.setStatus(POSTED);
		approval.setPostedTime(LocalDateTime.now());
		require(mapper.updateById(approval) == 1, "盘点审批调账回执保存失败，本次调账已回滚");
	}

	public void requireNoActive(Long planDetailId) {
		var approval = latest(planDetailId, true);
		require(approval == null || REJECTED.equals(approval.getStatus()) || REVOKED.equals(approval.getStatus()),
				"该盘点明细已有待审核或已通过的差异申请，请先处理原申请");
	}

	private WmsInventoryAdjustmentApprovalEntity approval(Long id, boolean lock) {
		var result = mapper.selectOne(Wrappers.<WmsInventoryAdjustmentApprovalEntity>lambdaQuery()
				.eq(WmsInventoryAdjustmentApprovalEntity::getId, id)
				.eq(WmsInventoryAdjustmentApprovalEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(WmsInventoryAdjustmentApprovalEntity::getFactoryCode, factory())
				.last(lock ? "FOR UPDATE" : ""));
		require(result != null, "盘点审批申请不存在或不属于当前范围");
		return result;
	}

	private WmsInventoryAdjustmentApprovalEntity latest(Long detailId, boolean lock) {
		return mapper.selectOne(Wrappers.<WmsInventoryAdjustmentApprovalEntity>lambdaQuery()
				.eq(WmsInventoryAdjustmentApprovalEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(WmsInventoryAdjustmentApprovalEntity::getFactoryCode, factory())
				.eq(WmsInventoryAdjustmentApprovalEntity::getPlanDetailId, detailId)
				.orderByDesc(WmsInventoryAdjustmentApprovalEntity::getId)
				.last("LIMIT 1" + (lock ? " FOR UPDATE" : "")));
	}

	private String actor() {
		MomUser user = SecurityUtils.getUser();
		require(user != null && StrUtil.isNotBlank(user.getUsername()), "无法识别当前操作人");
		return user.getUsername();
	}

}
