package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;
import com.kitex.mom.wms.mapper.WmsInventoryVariancePolicyMapper;
import com.kitex.mom.wms.service.WmsInventoryVariancePolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Objects;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.*;

/**
 * 对原盘点明细执行只读门禁核对，审批时保存该次依据。
 */
@Service
@RequiredArgsConstructor
public class WmsInventoryVarianceGate {
	private final WmsInventoryVariancePolicyService policies;
	private final WmsInventoryVariancePolicyMapper mapper;
	private final WmsInventoryPlanAccess access;
	private final WmsInventoryWarehousePermission permissions;
	private final ObjectMapper json;
	private final WmsInventoryStockCheckService stockChecks;

	@Transactional(rollbackFor = Exception.class)
	public Check inspect(Long planDetailId) {
		require(planDetailId != null, "请选择盘点明细");
		var row = access.detail(planDetailId);
		permissions.require("view", row.getWarehouseCode());
		return check(row);
	}

	/**
	 * 调用方已持有原计划/明细事务锁；此处不创建复盘或代替审批。
	 */
	public String freeze(WmsInsideInventoryPlanDetailEntity row) {
		require(row != null, "请选择盘点明细");
		permissions.require("hand", row.getWarehouseCode());
		Check check = check(row);
		require(check.stockCheck().eligible(), check.stockCheck().message());
		require(check.canSubmit(), "本次差异须先复盘，请在原盘点明细完成复盘后重新申请");
		try {
			return json.writeValueAsString(check);
		} catch (JsonProcessingException ex) {
			throw new CheckedException("盘点规则依据保存失败，请重试");
		}
	}

	/**
	 * 外部受托服务已核实际厂仓写岗位；仍须同一原仓政策与真实单组复盘。
	 */
	public String externalPolicy(WmsInsideInventoryPlanDetailEntity row) {
		Check checked = check(row);
		require(checked.recountSatisfied() && checked.difference().signum() < 0
				&& "EXTERNAL_OWNERSHIP".equals(checked.stockCheck().status())
				&& "DIFFERENCE".equals(checked.stockCheck().ownerReview().status())
				&& checked.stockCheck().ownerReview().groups().size() == 1, "外部盘亏须有有效单组复盘");
		try {
			return json.writeValueAsString(checked);
		} catch (JsonProcessingException ex) {
			throw new CheckedException("外部差异政策依据保存失败");
		}
	}

	private Check check(WmsInsideInventoryPlanDetailEntity row) {
		require(Objects.equals(TenantContextHolder.getTenantId(), row.getTenantId())
						&& Objects.equals(factory(), row.getFactoryCode()) && row.getId() != null
						&& row.getPlanId() != null && StrUtil.isNotBlank(row.getWarehouseCode())
						&& "1".equals(row.getStatus()) && "0".equals(row.getPlStatus()),
				"盘点明细范围无效、尚未实盘完成或已调账");
		BigDecimal book = number(row.getCurrentNumber(), "账面数量");
		BigDecimal counted = number(row.getInventoryNum(), "实盘数量");
		var policy = policies.current(row.getWarehouseCode());
		require(policy != null && Objects.equals(row.getWarehouseCode(), policy.warehouseCode()), "差异规则仓库不一致");
		boolean required = WmsInventoryVariancePolicyService.requiresRecount(policy, book, counted);
		var record = row.getLastRecountId() == null
				? mapper.latestRecount(TenantContextHolder.getTenantId(), factory(), row.getPlanId(), row.getId())
				: mapper.recountById(TenantContextHolder.getTenantId(), factory(), row.getId(), row.getLastRecountId());
		boolean satisfied = record != null && record.getId() != null
				&& Objects.equals(row.getTenantId(), record.getTenantId())
				&& Objects.equals(row.getFactoryCode(), record.getFactoryCode())
				&& Objects.equals(row.getPlanId(), record.getPlanId())
				&& Objects.equals(row.getId(), record.getPlanDetailId())
				&& record.getRecountNum() != null && Double.isFinite(record.getRecountNum())
				&& counted.compareTo(BigDecimal.valueOf(record.getRecountNum())) == 0
				&& record.getPreviousNum() != null && Double.isFinite(record.getPreviousNum())
				&& record.getPreviousNum() >= 0 && StrUtil.isNotBlank(record.getOperatorCode())
				&& StrUtil.isNotBlank(record.getReason()) && record.getOperatedTime() != null
				&& record.getRequestId() != null && record.getRequestId().matches("[A-Za-z0-9_-]{16,64}");
		var stockCheck = stockChecks.inspect(row);
		return new Check(policy, book, counted, counted.subtract(book), required, satisfied,
				satisfied ? record.getId().toString() : null, stockCheck.eligible() && (!required || satisfied),
				stockCheck);
	}

	public record Check(WmsInventoryVariancePolicyService.Policy policy, BigDecimal bookNumber,
	                    BigDecimal countedNumber, BigDecimal difference, boolean recountRequired,
	                    boolean recountSatisfied, String recountRecordId, boolean canSubmit,
	                    WmsInventoryStockCheckService.Check stockCheck) {
	}
}
