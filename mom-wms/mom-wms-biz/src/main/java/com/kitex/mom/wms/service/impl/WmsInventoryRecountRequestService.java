package com.kitex.mom.wms.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.wms.dto.WmsInventoryRecountTerminationCommand;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;
import com.kitex.mom.wms.entity.WmsInventoryRecountRecordEntity;
import com.kitex.mom.wms.mapper.WmsInventoryRecountRecordMapper;
import com.kitex.mom.wms.mapper.WmsInventoryRecountRequestMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.number;
import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;

/**
 * 查回或安全结束原请求；不撤销已有结果，不依赖当前实盘、手机开关及计划可写状态。
 */
@Service
@RequiredArgsConstructor
public class WmsInventoryRecountRequestService {
	private final WmsInventoryPlanAccess access;
	private final WmsInventoryOwnerCountService owner;
	private final PermissionService permissions;
	private final WmsInventoryRecountRecordMapper records;
	private final WmsInventoryRecountRequestMapper requests;
	private final WmsInventoryRecountRequestCodec codec;
	private final WmsInventoryOwnerCountEvidence evidence;

	@Transactional(rollbackFor = Exception.class)
	public Resolution resolve(Long detailId, String requestId) {
		var row = access.detail(detailId);
		owner.context(row.getId());
		return resolve(row, requestId);
	}

	private Resolution resolve(WmsInsideInventoryPlanDetailEntity row, String requestId) {
		require(requestId != null && requestId.matches("[A-Za-z0-9_-]{16,64}"), "原复盘请求号无效");
		var record = records.selectOne(Wrappers.<WmsInventoryRecountRecordEntity>lambdaQuery()
				.eq(WmsInventoryRecountRecordEntity::getTenantId, row.getTenantId())
				.eq(WmsInventoryRecountRecordEntity::getFactoryCode, row.getFactoryCode())
				.eq(WmsInventoryRecountRecordEntity::getRequestId, requestId));
		var state = requests.find(row.getTenantId(), row.getFactoryCode(), requestId);
		if (state != null) {
			require(Objects.equals(state.tenantId(), row.getTenantId()) &&
					Objects.equals(state.factoryCode(), row.getFactoryCode())
					&& Objects.equals(state.requestId(), requestId) &&
					Objects.equals(state.planId(), row.getPlanId()) &&
					Objects.equals(state.planDetailId(), row.getId()), "原请求不属于当前盘点范围");
		}
		if (record != null) {
			require(Objects.equals(record.getTenantId(), row.getTenantId()) &&
					Objects.equals(record.getFactoryCode(), row.getFactoryCode()) &&
					Objects.equals(record.getRequestId(), requestId) &&
					Objects.equals(record.getPlanId(), row.getPlanId()) &&
					Objects.equals(record.getPlanDetailId(), row.getId()), "请求号不属于原盘点明细");
			require(state != null && "RECORDED".equals(state.status()) &&
					Objects.equals(state.recordId(), record.getId()), "原复盘登记索引未核定，请联系管理员核对");
			return new Resolution(1, "RECORDED", record, null);
		}
		if (state == null) {
			return new Resolution(1, "UNSEEN", null, null);
		}
		require(Objects.equals(state.tenantId(), row.getTenantId()) &&
						Objects.equals(state.factoryCode(), row.getFactoryCode())
						&& Objects.equals(state.planId(), row.getPlanId()) && Objects.equals(state.planDetailId(), row.getId())
						&& Objects.equals(state.warehouseCode(), row.getWarehouseCode()) && "TERMINATED".equals(state.status()),
				"原请求范围或登记状态不一致，请核对原件");
		return new Resolution(1, "TERMINATED", null, state);
	}

	@Transactional(rollbackFor = Exception.class)
	public Resolution terminate(WmsInventoryRecountTerminationCommand command) {
		require(command != null, "请提供原复盘请求");
		var original = command.getOriginal();
		var frozen = codec.encode(original);
		require(command.getReason() != null && !command.getReason().isBlank() &&
				command.getReason().trim().length() <= 500, "请填写500字以内的结束原因");
		var row = access.detail(original.getPlanDetailId());
		var context = owner.context(row.getId());
		String base = "wms_wmsInsideInventoryPlan_hand";
		require(permissions.hasPermission(base, base + "_" + row.getWarehouseCode())
				|| permissions.hasPermission("wms_wmsInsideInventoryPlan_edit"), "无原仓库复盘处理权限");
		var result = resolve(row, original.getRequestId());
		if ("RECORDED".equals(result.status())) {
			var record = result.record();
			require(number(original.getExpectedInventoryNum(), "原实盘数量").compareTo(
					number(record.getPreviousNum(), "原记录前数量")) == 0
					&& evidence.proposedTotal(original).compareTo(number(record.getRecountNum(), "原记录数量")) == 0
					&& original.getReason().trim().equals(record.getReason()) &&
					evidence.matchesOriginal(record.getOwnerCountJson(), original), "原请求已用于其他复盘内容");
			return result;
		}
		if ("TERMINATED".equals(result.status())) {
			require(frozen.equals(result.termination().commandJson()), "已结束请求的原文不一致");
			return result;
		}
		var ended = new WmsInventoryRecountRequestMapper.Row(row.getTenantId(), row.getFactoryCode(),
				original.getRequestId(),
				row.getPlanId(), row.getId(), "TERMINATED", null, row.getWarehouseCode(), frozen,
				context.operatorCode(),
				command.getReason().trim(), LocalDateTime.now());
		require(requests.insertTermination(ended) == 1, "结束凭据未可靠保存，原请求仍需核对");
		return new Resolution(1, "TERMINATED", null, ended);
	}

	public record Resolution(int contractVersion, String status, WmsInventoryRecountRecordEntity record,
	                         WmsInventoryRecountRequestMapper.Row termination) {
	}
}
