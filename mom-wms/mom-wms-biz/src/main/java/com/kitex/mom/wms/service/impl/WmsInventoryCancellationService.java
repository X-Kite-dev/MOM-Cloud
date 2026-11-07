package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.dto.WmsInventoryCancelCommand;
import com.kitex.mom.wms.entity.WmsInBillEntity;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.mapper.WmsInBillMapper;
import com.kitex.mom.wms.mapper.WmsInsideInventoryPlanMapper;
import com.kitex.mom.wms.mapper.WmsOutBillMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.factory;
import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.number;
import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;

/**
 * 作废过期盘点快照，保留原计划、明细和实盘事实。
 */
@Service
@RequiredArgsConstructor
public class WmsInventoryCancellationService {
	private final WmsInventoryPlanAccess access;
	private final WmsInsideInventoryPlanMapper plans;
	private final WmsInBillMapper inBills;
	private final WmsOutBillMapper outBills;
	private final WmsInventoryAdjustmentApprovalService approvals;

	@Transactional(rollbackFor = Exception.class)
	public boolean cancel(WmsInventoryCancelCommand command) {
		require(command != null && command.getId() != null, "请选择盘点计划");
		String requestId = StrUtil.trim(command.getRequestId());
		String reason = StrUtil.trim(command.getReason());
		require(requestId != null && requestId.matches("[A-Za-z0-9_-]{16,64}"), "作废请求号格式不正确");
		require(StrUtil.isNotBlank(reason) && reason.length() <= 500, "作废原因必填且最多500字");

		var plan = access.plan(command.getId(), true);
		if ("2".equals(plan.getStatus())) {
			require("2".equals(plan.getHandleState()) && requestId.equals(plan.getCancelRequestId())
					&& reason.equals(plan.getCancelReason()), "盘点计划已作废，原请求内容不一致");
			return true;
		}
		require(("0".equals(plan.getStatus()) || "1".equals(plan.getStatus()))
				&& "0".equals(plan.getHandleState()), "仅未完成损益处理的盘点允许作废");
		requireUnposted(access.details(plan.getId(), true));
		MomUser user = SecurityUtils.getUser();
		require(user != null && StrUtil.isNotBlank(user.getUsername()), "无法识别当前作废人");
		plan.setStatus("2");
		plan.setHandleState("2");
		plan.setCancelRequestId(requestId);
		plan.setCancelReason(reason);
		plan.setCancelBy(user.getUsername());
		plan.setCancelTime(LocalDateTime.now());
		require(plans.updateById(plan) == 1, "盘点计划作废失败");
		return true;
	}

	/**
	 * 作废和重新盘点共用未记账校验；调用方已锁定原计划与明细。
	 */
	public void requireUnposted(java.util.List<WmsInsideInventoryPlanDetailEntity> rows) {
		for (WmsInsideInventoryPlanDetailEntity row : rows) {
			if ("1".equals(row.getPlStatus())) {
				require(number(row.getCurrentNumber(), "原账面数量")
								.compareTo(number(row.getInventoryNum(), "实盘数量")) == 0,
						"已有明细完成差异调账，不能作废整张盘点计划");
			}
			require(noAdjustmentBill(row.getId()), "已有盘点损益单据，不能作废整张盘点计划");
			approvals.requireNoActive(row.getId());
		}
	}

	private boolean noAdjustmentBill(Long detailId) {
		Long inbound = inBills.selectCount(Wrappers.<WmsInBillEntity>lambdaQuery()
				.eq(WmsInBillEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(WmsInBillEntity::getFactoryCode, factory())
				.eq(WmsInBillEntity::getDocumentTable, WmsInventoryAdjustmentContext.DOCUMENT)
				.eq(WmsInBillEntity::getDocumentId, detailId));
		Long outbound = outBills.selectCount(Wrappers.<WmsOutBillEntity>lambdaQuery()
				.eq(WmsOutBillEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(WmsOutBillEntity::getFactoryCode, factory())
				.eq(WmsOutBillEntity::getDocumentTable, WmsInventoryAdjustmentContext.DOCUMENT)
				.eq(WmsOutBillEntity::getDocumentId, detailId));
		return Objects.equals(inbound, 0L) && Objects.equals(outbound, 0L);
	}
}
