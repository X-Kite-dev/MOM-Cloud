package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONUtil;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
		String reason = StrUtil.trim(command.getReason());
		require(requestId != null && requestId.matches("[A-Za-z0-9_-]{16,64}"), "审批请求号格式不正确");
		require(StrUtil.isNotBlank(reason) && reason.length() <= 500, "差异申请原因不能为空且不能超过500字");
		var row = access.detail(command.getPlanDetailId());
		var plan = access.plan(row.getPlanId(), true);
		require("1".equals(plan.getStatus()) && "0".equals(plan.getHandleState())
						&& "1".equals(row.getStatus()) && "0".equals(row.getPlStatus())
						&& Objects.equals(plan.getWarehouseCode(), row.getWarehouseCode()),
				"盘点差异尚未实盘完成或已调账，不能提交审批");
		BigDecimal current = number(row.getCurrentNumber(), "账面数量");
		BigDecimal counted = number(row.getInventoryNum(), "实盘数量");
		require(current.compareTo(counted) != 0, "零差异无需提交损益审批");
		require(current.compareTo(number(command.getExpectedCurrentNumber(), "页面账面数量")) == 0
						&& counted.compareTo(number(command.getExpectedInventoryNum(), "页面实盘数量")) == 0,
				"盘点数量已变化，请刷新后重新提交审批");
		WmsInBillDetailEntity selected = command.getDetail();
		require(selected != null, "请选择损益批次及物料属性");
		require(StrUtil.isBlank(row.getMatBatch()) || Objects.equals(row.getMatBatch(), selected.getMatBatch()),
				"申请批次与原盘点范围不一致");
		if (counted.compareTo(current) > 0) {
			require(StrUtil.isNotBlank(selected.getMatBatch()) && StrUtil.isNotBlank(selected.getSupplierCode())
							&& StrUtil.isNotBlank(selected.getSupplierName()) && selected.getMatProduceDate() != null,
					"盘盈申请必须明确批次、供应商和生产日期");
		}
		String selection = selection(selected);
		var priorRequest = mapper.selectOne(Wrappers.<WmsInventoryAdjustmentApprovalEntity>lambdaQuery()
				.eq(WmsInventoryAdjustmentApprovalEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(WmsInventoryAdjustmentApprovalEntity::getFactoryCode, factory())
				.eq(WmsInventoryAdjustmentApprovalEntity::getRequestId, requestId));
		if (priorRequest != null) {
			require(Objects.equals(priorRequest.getPlanDetailId(), row.getId())
							&& current.compareTo(number(priorRequest.getExpectedCurrentNumber(), "原申请账面数量")) == 0
							&& counted.compareTo(number(priorRequest.getExpectedInventoryNum(), "原申请实盘数量")) == 0
							&& Objects.equals(priorRequest.getSelectedDetailJson(), selection)
							&& Objects.equals(priorRequest.getReason(), reason),
					"审批请求号已用于不同内容，请核对原申请");
			return priorRequest;
		}
		var latest = latest(row.getId(), true);
		require(latest == null || REJECTED.equals(latest.getStatus()) || REVOKED.equals(latest.getStatus()),
				"该明细已有待审核或已通过的申请，请处理原申请");
		if (mobile) {
			warehouseGuard.checkMobile(plan.getWarehouseCode());
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
		require(approval != null && APPROVED.equals(approval.getStatus())
						&& Objects.equals(approval.getPlanId(), row.getPlanId())
						&& Objects.equals(approval.getWarehouseCode(), row.getWarehouseCode())
						&& number(row.getCurrentNumber(), "账面数量").compareTo(
						number(approval.getExpectedCurrentNumber(), "审批账面数量")) == 0
						&& number(row.getInventoryNum(), "实盘数量").compareTo(
						number(approval.getExpectedInventoryNum(), "审批实盘数量")) == 0
						&& Objects.equals(approval.getSelectedDetailJson(), selection(command.getDetail())),
				"盘点差异尚未审核通过或调账依据已变化");
		return approval;
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

	private String selection(WmsInBillDetailEntity selected) {
		require(selected != null, "损益物料属性不能为空");
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("matBatch", selected.getMatBatch());
		fields.put("supplierCode", selected.getSupplierCode());
		fields.put("supplierName", selected.getSupplierName());
		fields.put("containerCode", selected.getContainerCode());
		fields.put("matBarcode", selected.getMatBarcode());
		fields.put("matProduceDate", selected.getMatProduceDate() == null
				? null : selected.getMatProduceDate().toString());
		fields.put("matValidPeriod", selected.getMatValidPeriod());
		fields.put("matValidEndDate", selected.getMatValidEndDate() == null
				? null : selected.getMatValidEndDate().toString());
		return JSONUtil.toJsonStr(fields, JSONConfig.create().setIgnoreNullValue(false));
	}
}
