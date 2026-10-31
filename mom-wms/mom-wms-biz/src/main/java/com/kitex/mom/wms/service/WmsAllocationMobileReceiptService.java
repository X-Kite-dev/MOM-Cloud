package com.kitex.mom.wms.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.dto.app.WmsAllocationMobileReceiptDTO.View;
import com.kitex.mom.wms.dto.app.WmsAllocationMobileReceiptDTO.Cancellation;
import com.kitex.mom.wms.mapper.WmsAllocationMobileCancelReceiptMapper;
import com.kitex.mom.wms.mapper.WmsAllocationMobileCancelReceiptMapper.Receipt;
import com.kitex.mom.wms.entity.WmsAllocationBillEntity;
import com.kitex.mom.wms.mapper.WmsAllocationBillMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 查询仅使用同事务保存的原单据证据；停用仓库不会抹掉已办事实。 */
@Service
@RequiredArgsConstructor
public class WmsAllocationMobileReceiptService {
	private final WmsAllocationBillMapper bills;
	private final PermissionService permissions;
	private final WmsAllocationMobileCancelReceiptMapper cancelled;

	public View lookup(String requestId, String warehouseCode) {
		Scope scope = requireScope();
		require(requestId != null && !requestId.isBlank() && requestId.length() <= 64
				&& requestId.equals(requestId.trim()), "原调拨请求号无效");
		require(warehouseCode != null && !warehouseCode.isBlank() && warehouseCode.length() <= 100
				&& warehouseCode.equals(warehouseCode.trim()) && !warehouseCode.contains("*")
				&& !warehouseCode.contains("?"), "请选择原调出仓库");
		require(permissions.hasPermission("wms_wmsAllocationBill_add", "wms_wmsAllocationBill_add_" + warehouseCode),
				"当前账号无权核对该仓库调拨");
		var bill = findBill(scope, requestId, warehouseCode);
		var fence = cancelled.find(scope.tenantId(), requestId);
		require(bill == null || fence == null, "原请求同时存在调拨和撤销记录，请核对历史证据");
		if (bill != null) {
			return confirmed(bill, scope, requestId, warehouseCode);
		}
		if (fence != null) {
			return cancelled(fence, scope, requestId, warehouseCode);
		}
		return new View(1, scope.tenantId(), scope.factoryCode(), scope.operatorCode(), requestId, warehouseCode,
				"NOT_CONFIRMED", null, null, null, null, null, null, null);
	}

	/** 撤销与旧版执行入口共用事务锁；只封存未执行请求，不创建业务单或库存流水。 */
	@Transactional(rollbackFor = Exception.class)
	public View cancel(Cancellation input) {
		Scope scope = requireScope();
		require(input != null, "请选择需要撤销的原请求");
		var command = input.command();
		WmsAllocationMobileCommand.normalize(command, scope.factoryCode());
		String reason = text(input.reason());
		require(!reason.isEmpty() && reason.length() <= 500, "请填写500字以内的撤销原因");
		String requestId = command.getRequestId();
		String warehouse = command.getOldWarehouseCode();
		require(permissions.hasPermission("wms_wmsAllocationBill_add", "wms_wmsAllocationBill_add_" + warehouse),
				"当前账号无权撤销该仓库调拨请求");
		require(bills.lockAgentTransfer("MOBILE_REQUEST:" + scope.tenantId() + ":" + requestId) == 1,
				"锁定移动调拨请求失败");
		String snapshot = WmsAllocationMobileCommand.snapshot(command);
		var bill = findBill(scope, requestId, warehouse);
		var fence = cancelled.find(scope.tenantId(), requestId);
		require(bill == null || fence == null, "原请求同时存在调拨和撤销记录，请核对历史证据");
		if (bill != null) {
			require(Objects.equals(snapshot, bill.getRequestSnapshot()), "调拨幂等号已被不同请求使用");
			return confirmed(bill, scope, requestId, warehouse);
		}
		if (fence == null) {
			fence = new Receipt(scope.tenantId(), requestId, scope.factoryCode(), scope.operatorCode(), warehouse,
					command.getBillNo(), snapshot, reason, LocalDateTime.now());
			require(cancelled.insert(fence) == 1, "原请求撤销保存失败");
			fence = cancelled.find(scope.tenantId(), requestId);
		}
		require(fence != null && Objects.equals(snapshot, fence.requestSnapshot()), "调拨幂等号已被不同请求使用");
		return cancelled(fence, scope, requestId, warehouse);
	}

	private WmsAllocationBillEntity findBill(Scope scope, String requestId, String warehouse) {
		return bills.selectOne(new LambdaQueryWrapper<WmsAllocationBillEntity>()
				.eq(WmsAllocationBillEntity::getTenantId, scope.tenantId())
				.eq(WmsAllocationBillEntity::getFactoryCode, scope.factoryCode())
				.eq(WmsAllocationBillEntity::getOldWarehouseCode, warehouse)
				.eq(WmsAllocationBillEntity::getRequestId, requestId));
	}

	public static View cancelled(Receipt receipt, Scope scope, String requestId, String warehouse) {
		require(receipt != null && Objects.equals(receipt.tenantId(), scope.tenantId())
				&& Objects.equals(receipt.factoryCode(), scope.factoryCode())
				&& Objects.equals(receipt.operatorCode(), scope.operatorCode())
				&& Objects.equals(receipt.requestId(), requestId) && Objects.equals(receipt.warehouseCode(), warehouse),
				"原撤销请求不属于当前租户、工厂、仓库或操作人");
		require(receipt.completedTime() != null && !text(receipt.cancellationReason()).isEmpty(), "原撤销回执不完整");
		Map<String, Object> payload = payload(receipt.requestSnapshot(), false);
		String target = payload.get("targetWarehouse").toString();
		require(Objects.equals(payload.get("factory"), scope.factoryCode())
				&& Objects.equals(payload.get("sourceWarehouse"), warehouse) && !target.isEmpty() && !target.equals(warehouse)
				&& Objects.equals(payload.get("billNo"), receipt.billNo()), "原撤销快照与单号或组织不一致");
		return new View(1, scope.tenantId(), scope.factoryCode(), scope.operatorCode(), requestId, warehouse, "CANCELLED",
				null, receipt.billNo(), target, (BigDecimal) payload.get("quantity"), JSONUtil.toJsonStr(payload),
				receipt.completedTime(), receipt.cancellationReason());
	}

	public static Scope requireScope() {
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		var actor = SecurityUtils.getUser();
		require(tenant != null && tenant > 0 && !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
				&& !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
				&& factory != null && !factory.isBlank() && actor != null && actor.getUsername() != null
				&& !actor.getUsername().isBlank(), "调拨办理和核对需要实际租户、工厂及登录身份");
		return new Scope(tenant, factory, actor.getUsername());
	}

	public static void requireOriginal(WmsAllocationBillEntity bill, Scope scope, String requestId, String warehouse) {
		require(bill != null && Objects.equals(bill.getTenantId(), scope.tenantId())
				&& Objects.equals(bill.getFactoryCode(), scope.factoryCode())
				&& Objects.equals(bill.getRequestId(), requestId)
				&& Objects.equals(bill.getOldWarehouseCode(), warehouse)
				&& Objects.equals(bill.getIssueBy(), scope.operatorCode()), "原调拨不属于当前租户、工厂、仓库或操作人");
	}

	public static View confirmed(WmsAllocationBillEntity bill, Scope scope, String requestId, String warehouse) {
		requireOriginal(bill, scope, requestId, warehouse);
		require(bill.getId() != null && bill.getId() > 0 && !text(bill.getBillNo()).isEmpty()
				&& Boolean.TRUE.equals(bill.getSourceDeducted()) && bill.getIssueTime() != null && bill.getFinishTime() != null
				&& Objects.equals(requestId, bill.getReceiveRequestId()) && bill.getRequestSnapshot() != null
				&& Objects.equals(bill.getRequestSnapshot(), bill.getReceiveRequestSnapshot()),
				"历史调拨缺少完整即时完成证据，请核对原单据与出入库流水");
		Map<String, Object> payload = payload(bill.getRequestSnapshot(), true);
		require(Objects.equals(payload.get("billNo"), bill.getBillNo())
				&& Objects.equals(payload.get("factory"), scope.factoryCode())
				&& Objects.equals(payload.get("sourceWarehouse"), warehouse)
				&& Objects.equals(payload.get("targetWarehouse"), bill.getNewWarehouseCode())
				&& !text(bill.getNewWarehouseCode()).isEmpty() && !warehouse.equals(bill.getNewWarehouseCode()),
				"原调拨快照与单据仓库或组织不一致");
		return new View(1, scope.tenantId(), scope.factoryCode(), scope.operatorCode(), requestId, warehouse, "CONFIRMED",
				bill.getId(), bill.getBillNo(), bill.getNewWarehouseCode(), (BigDecimal) payload.get("quantity"),
				JSONUtil.toJsonStr(payload), bill.getIssueTime(), null);
	}

	private static Map<String, Object> payload(String snapshot, boolean completed) {
		try {
			JSONObject original = JSONUtil.parseObj(snapshot);
			Object rawQuantity = original.get("quantity");
			require(rawQuantity instanceof Number && Double.isFinite(((Number) rawQuantity).doubleValue())
					&& new BigDecimal(rawQuantity.toString()).signum() > 0, "原调拨数量快照无效");
			Map<String, Object> payload = new LinkedHashMap<>();
			for (String field : List.of("billNo", "factory", "sourceWarehouse", "sourceSite", "sourceContainer",
					"targetWarehouse", "targetSite", "targetContainer", "material", "batch", "supplier", "unit", "matBarcode")) {
				Object value = original.get(field);
				require(value == null || JSONUtil.isNull(value) || value instanceof String, "原调拨字段快照类型无效");
				payload.put(field, value instanceof String ? text((String) value) : "");
			}
			for (String field : List.of("sourceSite", "targetSite", "material")) {
				require(!payload.get(field).toString().isEmpty(), "原调拨来源快照不完整");
			}
			require(!completed || !payload.get("unit").toString().isEmpty(), "原调拨单位快照不完整");
			Object sourceStock = original.get("sourceStockId");
			String stockId = sourceStock == null || JSONUtil.isNull(sourceStock) ? "" : sourceStock.toString();
			require(stockId.isEmpty() || (stockId.matches("[1-9][0-9]{0,18}") && Long.parseLong(stockId) > 0),
					"原调拨实物库存标识无效");
			payload.put("sourceStockId", stockId);
			payload.put("quantity", new BigDecimal(rawQuantity.toString()));
			return payload;
		} catch (CheckedException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new CheckedException("原调拨快照无法读取，请核对历史单据");
		}
	}

	public record Scope(Long tenantId, String factoryCode, String operatorCode) {
	}

	private static String text(String value) {
		return value == null ? "" : value.trim();
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}
}
