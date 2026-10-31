package com.kitex.mom.wms.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.wms.dto.app.WmsMobileTransferReceiptDTO.Command;
import com.kitex.mom.wms.dto.app.WmsMobileTransferReceiptDTO.Context;
import com.kitex.mom.wms.dto.app.WmsMobileTransferReceiptDTO.Source;
import com.kitex.mom.wms.dto.app.WmsMobileTransferReceiptDTO.View;
import com.kitex.mom.wms.entity.WmsAllocationBillEntity;
import com.kitex.mom.wms.mapper.WmsAllocationBillMapper;
import com.kitex.mom.wms.mapper.WmsTransferReceiptMapper;
import com.kitex.mom.wms.service.WmsAllocationMobileReceiptService.Scope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;

/**
 * 标准接收的移动适配层：实际入库及差异治理由既有业务事务完成。
 */
@Service
@RequiredArgsConstructor
public class WmsMobileTransferReceiptService {
	private final WmsAllocationBillMapper bills;
	private final WmsTransferReceiptMapper receipts;
	private final WmsAllocationBillService allocations;
	private final WmsTransferVarianceService receiving;
	private final PermissionService permissions;
	private final WmsWarehouseBusinessGuard warehouseGuard;

	public Context context() {
		var scope = WmsAllocationMobileReceiptService.requireScope();
		return new Context(1, scope.tenantId(), scope.factoryCode(), scope.operatorCode());
	}

	public Source sources(String billNo, String warehouseCode) {
		var scope = WmsAllocationMobileReceiptService.requireScope();
		String warehouse = warehouse(warehouseCode, false);
		require(billNo != null && !billNo.isBlank() && billNo.length() <= 100, "请输入调拨单号");
		var bill = bills.selectOne(query(scope, warehouse).eq(WmsAllocationBillEntity::getBillNo, billNo.trim()));
		checkBill(bill, scope, warehouse);
		var detail = allocations.getDetails(bill.getId());
		require(detail != null && Objects.equals(detail.getId(), bill.getId())
				&& Objects.equals(detail.getNewWarehouseCode(), warehouse)
				&& Objects.equals(detail.getFactoryCode(), scope.factoryCode()), "调拨明细归属无法确认");
		return new Source(context(), warehouse, detail);
	}

	@Transactional(rollbackFor = Exception.class)
	public View receive(Command command) {
		var scope = WmsAllocationMobileReceiptService.requireScope();
		String warehouse = warehouse(command == null ? null : command.warehouseCode(), true);
		String snapshot = prepare(command);
		var bill = bill(scope, command.receipt().getAllocationBillId(), warehouse, true);
		var original = original(scope, bill, warehouse, command.receipt().getRequestId());
		if (!"NOT_CONFIRMED".equals(original.status())) {
			require(Objects.equals(original.requestPayload(), WmsTransferReceiptSnapshot.payload(snapshot)),
					"接收原号已被不同内容使用");
			require("CONFIRMED".equals(original.status()), "原接收请求已取消，请查询原结果后重新办理");
			return original;
		}
		warehouseGuard.checkMobile(warehouse);
		receiving.receive(command.receipt());
		var confirmed = original(scope, bill, warehouse, command.receipt().getRequestId());
		require("CONFIRMED".equals(confirmed.status())
						&& Objects.equals(confirmed.requestPayload(), WmsTransferReceiptSnapshot.payload(snapshot)),
				"接收原回执尚未形成完整证据");
		return confirmed;
	}

	public View lookup(String requestId, Long billId, String warehouseCode) {
		var scope = WmsAllocationMobileReceiptService.requireScope();
		String warehouse = warehouse(warehouseCode, true);
		requestId(requestId);
		return original(scope, bill(scope, billId, warehouse, false), warehouse, requestId);
	}

	@Transactional(rollbackFor = Exception.class)
	public View cancel(Command command, String reason) {
		var scope = WmsAllocationMobileReceiptService.requireScope();
		String warehouse = warehouse(command == null ? null : command.warehouseCode(), true);
		String snapshot = prepare(command);
		require(reason != null && !reason.isBlank() && reason.trim().length() <= 500, "请填写500字以内的取消原因");
		var bill = bill(scope, command.receipt().getAllocationBillId(), warehouse, true);
		String request = command.receipt().getRequestId();
		var original = original(scope, bill, warehouse, request);
		if ("NOT_CONFIRMED".equals(original.status())) {
			require(receipts.insertCancellation(new WmsTransferReceiptMapper.Cancellation(scope.tenantId(), request,
							scope.factoryCode(), scope.operatorCode(), warehouse, bill.getId(), snapshot, reason.trim(), LocalDateTime.now())) == 1,
					"原接收请求取消保存失败");
			original = original(scope, bill, warehouse, request);
		}
		require(Objects.equals(original.requestPayload(), WmsTransferReceiptSnapshot.payload(snapshot)), "接收原号已被不同内容使用");
		return original;
	}

	private String prepare(Command command) {
		require(command != null && command.receipt() != null, "请选择需要办理的调拨单");
		var receipt = command.receipt();
		requestId(receipt.getRequestId());
		require(receipt.getAllocationBillId() != null && receipt.getAllocationBillId() > 0
				&& receipt.getFinalReceipt() != null && receipt.getLines() != null
				&& !receipt.getLines().isEmpty() && receipt.getLines().size() <= 500, "接收明细不完整");
		String raw = WmsTransferReceiptSnapshot.raw(receipt);
		WmsTransferReceiptSnapshot.payload(raw);
		return raw;
	}

	private WmsAllocationBillEntity bill(Scope scope, Long id, String warehouse, boolean lock) {
		require(id != null && id > 0, "请选择调拨单");
		var query = query(scope, warehouse).eq(WmsAllocationBillEntity::getId, id);
		if (lock) {
			query.last("FOR UPDATE");
		}
		var bill = bills.selectOne(query);
		checkBill(bill, scope, warehouse);
		return bill;
	}

	private LambdaQueryWrapper<WmsAllocationBillEntity> query(Scope scope, String warehouse) {
		return new LambdaQueryWrapper<WmsAllocationBillEntity>().eq(WmsAllocationBillEntity::getTenantId, scope.tenantId())
				.eq(WmsAllocationBillEntity::getFactoryCode, scope.factoryCode())
				.eq(WmsAllocationBillEntity::getNewWarehouseCode, warehouse);
	}

	private void checkBill(WmsAllocationBillEntity bill, Scope scope, String warehouse) {
		require(bill != null && bill.getId() != null && bill.getId() > 0 && !text(bill.getBillNo()).isEmpty()
				&& Objects.equals(bill.getTenantId(), scope.tenantId()) && Objects.equals(bill.getFactoryCode(), scope.factoryCode())
				&& Objects.equals(bill.getNewWarehouseCode(), warehouse), "未找到当前仓库可办理的调拨单");
		require(Boolean.TRUE.equals(bill.getSourceDeducted()), "该历史调拨单请从电脑端办理完整接收");
	}

	private View original(Scope scope, WmsAllocationBillEntity bill, String warehouse, String request) {
		var receipt = receipts.findOriginal(scope.tenantId(), request);
		var cancelled = receipts.cancelled(scope.tenantId(), request);
		require(receipt == null || cancelled == null, "原接收同时存在完成和取消记录，请核对历史");
		if (receipt != null) {
			require(Objects.equals(receipt.getTenantId(), scope.tenantId()) && Objects.equals(receipt.getFactoryCode(), scope.factoryCode())
							&& Objects.equals(receipt.getReceiverCode(), scope.operatorCode())
							&& Objects.equals(receipt.getAllocationBillId(), bill.getId())
							&& Objects.equals(receipt.getRequestId(), request) && Objects.equals(receipt.getAllocationBillNo(), bill.getBillNo()),
					"原接收回执不属于当前单据、工厂或操作人");
			require(receipt.getId() != null && receipt.getId() > 0 && !text(receipt.getReceiptNo()).isEmpty()
							&& receipt.getReceiptTime() != null
							&& Set.of("PARTIAL", "COMPLETE", "VARIANCE_PENDING").contains(receipt.getReceiptStatus())
							&& ("PARTIAL".equals(receipt.getReceiptStatus()) != Boolean.TRUE.equals(receipt.getFinalReceipt())),
					"原接收回执尚未完整保存");
			String payload = WmsTransferReceiptSnapshot.payload(receipt.getRequestSnapshot());
			var parsed = JSONUtil.parseObj(payload);
			require(Objects.equals(parsed.getStr("billId"), bill.getId().toString())
							&& Objects.equals(parsed.getBool("final"), receipt.getFinalReceipt())
							&& Objects.equals(parsed.getJSONArray("lines").size(), receipt.getLineCount())
							&& ("VARIANCE_PENDING".equals(receipt.getReceiptStatus()) == (receipt.getVarianceCaseId() != null)),
					"原接收批次与请求证据不一致");
			return new View(1, scope.tenantId(), scope.factoryCode(), scope.operatorCode(), request, warehouse,
					bill.getId(), bill.getBillNo(),
					"CONFIRMED", receipt.getId(), receipt.getReceiptNo(), receipt.getReceiptStatus(), receipt.getVarianceCaseId(),
					payload, receipt.getReceiptTime(), null);
		}
		if (cancelled != null) {
			require(Objects.equals(cancelled.tenantId(), scope.tenantId()) && Objects.equals(cancelled.factoryCode(), scope.factoryCode())
					&& Objects.equals(cancelled.operatorCode(), scope.operatorCode())
					&& Objects.equals(cancelled.warehouseCode(), warehouse)
					&& Objects.equals(cancelled.allocationBillId(), bill.getId()) && Objects.equals(cancelled.requestId(), request)
					&& cancelled.completedTime() != null && !text(cancelled.reason()).isEmpty(), "原取消记录不属于当前作业");
			String payload = WmsTransferReceiptSnapshot.payload(cancelled.requestSnapshot());
			require(Objects.equals(JSONUtil.parseObj(payload).getStr("billId"), bill.getId().toString()), "原取消单据证据不一致");
			return new View(1, scope.tenantId(), scope.factoryCode(), scope.operatorCode(), request, warehouse,
					bill.getId(), bill.getBillNo(),
					"CANCELLED", null, null, null, null, payload, cancelled.completedTime(), cancelled.reason());
		}
		return new View(1, scope.tenantId(), scope.factoryCode(), scope.operatorCode(), request, warehouse,
				bill.getId(), bill.getBillNo(),
				"NOT_CONFIRMED", null, null, null, null, null, null, null);
	}

	private String warehouse(String code, boolean write) {
		require(code != null && !code.isBlank() && code.length() <= 100 && code.equals(code.trim()) && !code.matches(".*[?*].*"),
				"请从目标仓库的调拨接收入口办理");
		boolean allowed = write
				? permissions.hasPermission("wms_wmsAllocationReceive_rec", "wms_wmsAllocationReceive_rec_" + code)
				: permissions.hasPermission("wms_wmsAllocationReceive_rec", "wms_wmsAllocationReceive_rec_" + code,
				"wms_wmsAllocationReceive_view", "wms_wmsAllocationReceive_view_" + code);
		require(allowed, "当前账号无权办理该仓库的调拨接收");
		return code;
	}

	private void requestId(String value) {
		require(value != null && !value.isBlank() && value.length() <= 64 && value.equals(value.trim()), "原接收请求号无效");
	}

	private String text(String value) {
		return value == null ? "" : value.trim();
	}

	private void require(boolean valid, String message) {
		if (!valid) {
			throw new CheckedException(message);
		}
	}
}
