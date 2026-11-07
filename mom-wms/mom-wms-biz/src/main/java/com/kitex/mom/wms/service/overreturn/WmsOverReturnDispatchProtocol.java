package com.kitex.mom.wms.service.overreturn;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.mom.wms.dto.WmsOverReturnDTO.*;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import com.kitex.common.security.component.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.kitex.mom.wms.service.overreturn.WmsOverReturnRules.*;

/**
 * 派出版本化快照与原操作账负终态；不调用原处置服务以免循环依赖。
 */
@Component
@RequiredArgsConstructor
public class WmsOverReturnDispatchProtocol {
	private final WmsOverReturnAccess access;
	private final WmsOverReturnCodec codec;
	private final WmsOverReturnStore store;
	private final WmsTransferVarianceCaseMapper cases;
	private final WmsTransferVarianceOperationMapper operations;
	private final WmsWarehouseBusinessGuard business;
	private final PermissionService permissions;

	public DispatchContext context(String caseId) {
		var s = access.scope();
		var c = original(id(caseId), false);
		require(permissions.hasPermission("wms_transferVariance_view") &&
				(access.allowed(c.getTargetWarehouseCode(), "view", true) ||
						access.allowed(c.getTargetWarehouseCode(), "rec", true)), "无原目标仓差异查看资格");
		boolean end = permissions.hasPermission("wms_transferVariance_dispose") &&
				access.allowed(c.getTargetWarehouseCode(), "rec", true), dispatch = false;
		if (end) {
			try {
				access.writeFactory(s);
			} catch (com.kitex.common.core.exception.CheckedException e) {
				end = false;
			}
		}
		// 今日接收配置只限制新派出，不能剥夺仍有原岗位的安全结束资格。
		if (end) {
			try {
				dispatch = "PENDING_DISPOSITION".equals(c.getStatus()) &&
						business.check(c.getTargetWarehouseCode(), "allocation", true);
			} catch (com.kitex.common.core.exception.CheckedException e) {
				dispatch = false;
			}
		}
		return new DispatchContext(caseId, s.tenant().toString(), s.actor().toString(), s.factory(),
				c.getTargetWarehouseCode(), dispatch, end, "ORD1:" + s.actor() + ":" + UUID.randomUUID());
	}

	/**
	 * 原字段含显式null、数组顺序和文本ID数量；不trim客户端字段。
	 */
	public String snapshot(DispatchCommand command) {
		require(command != null && Integer.valueOf(1).equals(command.getV()) && command.getCaseId() != null &&
						command.getCaseId() > 0 && "RETURN_TO_SOURCE".equals(command.getOverDisposition()),
				"派出版本、原案或动作无效");
		var s = access.scope();
		var c = original(command.getCaseId(), false);
		require(command.getRequestId() != null && command.getRequestId()
						.matches("ORD1:" + s.actor() + ":[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"),
				"派出必须使用本人服务端原号");
		text(command.getDispatchEvidence(), 500, "运输凭据");
		text(command.getRemark(), 1000, "处置说明");
		Map<String, Object> scope = new LinkedHashMap<>();
		scope.put("tenantId", s.tenant().toString());
		scope.put("factoryCode", s.factory());
		scope.put("actorId", s.actor().toString());
		scope.put("targetWarehouseCode", c.getTargetWarehouseCode());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("v", 1);
		body.put("caseId", command.getCaseId().toString());
		body.put("requestId", command.getRequestId());
		body.put("shortDisposition", command.getShortDisposition());
		body.put("overDisposition", command.getOverDisposition());
		body.put("referenceNo", command.getReferenceNo());
		body.put("remark", command.getRemark());
		body.put("supplementRequestId", command.getSupplementRequestId());
		body.put("supplementBillNo", command.getSupplementBillNo());
		List<Map<String, Object>> allocations = null;
		if (command.getSupplementAllocations() != null) {
			require(command.getSupplementAllocations().size() <= 500, "补调片段过多");
			allocations = new ArrayList<>();
			for (var a : command.getSupplementAllocations()) {
				require(a != null && a.getVarianceLineId() != null && a.getVarianceLineId() > 0 &&
								a.getSourceStockId() != null && a.getSourceStockId() > 0 && a.getQuantity() != null,
						"补调分配身份缺失");
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("varianceLineId", a.getVarianceLineId().toString());
				m.put("sourceStockId", a.getSourceStockId().toString());
				m.put("quantity", decimal(quantity(decimal(a.getQuantity()))));
				allocations.add(m);
			}
		}
		body.put("supplementAllocations", allocations);
		body.put("dispatchEvidence", command.getDispatchEvidence());
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("contract", "OVER_RETURN_DISPATCH_V1");
		snapshot.put("scope", scope);
		snapshot.put("command", body);
		String value = codec.encode(snapshot);
		require(!value.contains("\\u0000") && value.length() <= 262144, "派出字段含NUL或全文过长");
		return value;
	}

	/**
	 * 原号锁后、业务状态判断前裁定永久终态；end只核静态来源和实际岗位。
	 */
	public DispatchResult prepare(DispatchCommand command, String action) {
		String snapshot = snapshot(command);
		var s = access.scope();
		var c = original(command.getCaseId(), false);
		require(permissions.hasPermission("wms_transferVariance_view") &&
				(access.allowed(c.getTargetWarehouseCode(), "view", true) ||
						access.allowed(c.getTargetWarehouseCode(), "rec", true)), "无原目标仓差异查看资格");
		store.lock(s.tenant(), command.getRequestId());
		c = original(command.getCaseId(), true);
		var op = operations.selectOne(new LambdaQueryWrapper<WmsTransferVarianceOperationEntity>().eq(
						WmsTransferVarianceOperationEntity::getTenantId, s.tenant())
				.eq(WmsTransferVarianceOperationEntity::getRequestId, command.getRequestId()));
		if (op != null) {
			require(Objects.equals(op.getCaseId(), c.getId()) && Objects.equals(op.getFactoryCode(), s.factory()) &&
							Objects.equals(op.getOperatorCode(), s.operator()) && snapshot.equals(op.getRequestSnapshot()) &&
							Set.of("DISPOSE", "DISPOSE_END").contains(op.getOperationType()) && op.getOperationTime() != null,
					"原派出号被异案、异人或异文使用");
			boolean applied = "DISPOSE".equals(op.getOperationType());
			require(!applied || "RETURN_IN_TRANSIT".equals(op.getAfterStatus()), "原派出回执残缺");
			return new DispatchResult(c.getId().toString(), command.getRequestId(), s.actor().toString(),
					applied ? "APPLIED" : "CANCELLED", applied ? op.getAfterStatus() : null,
					applied ? command.getDispatchEvidence() : null, applied ? eventTime(op.getOperationTime()) : null,
					eventTime(op.getOperationTime()));
		}
		if (!"lookup".equals(action)) {
			access.target(c.getTargetWarehouseCode());
			access.writeFactory(s);
			store.ready();
		}
		if ("end".equals(action)) {
			LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
			var end = new WmsTransferVarianceOperationEntity().setCaseId(c.getId()).setRequestId(command.getRequestId())
					.setRequestSnapshot(snapshot).setOperationType("DISPOSE_END").setBeforeStatus(c.getStatus())
					.setAfterStatus(c.getStatus()).setOperationContent("结束本人未受理退回发出原号")
					.setOperatorCode(s.operator()).setOperatorName(s.name() == null ? s.operator() : s.name())
					.setOperationTime(now).setFactoryCode(s.factory());
			require(operations.insert(end) == 1, "结束原号保存失败");
			return new DispatchResult(c.getId().toString(), command.getRequestId(), s.actor().toString(), "CANCELLED",
					null, null, null, eventTime(now));
		}
		return new DispatchResult(c.getId().toString(), command.getRequestId(), s.actor().toString(), "NOT_FOUND", null,
				null, null, null);
	}

	private WmsTransferVarianceCaseEntity original(long id, boolean lock) {
		var s = access.scope();
		var q = new LambdaQueryWrapper<WmsTransferVarianceCaseEntity>().eq(WmsTransferVarianceCaseEntity::getId, id)
				.eq(WmsTransferVarianceCaseEntity::getTenantId, s.tenant())
				.eq(WmsTransferVarianceCaseEntity::getFactoryCode, s.factory());
		if (lock) {
			q.last("FOR UPDATE");
		}
		var c = cases.selectOne(q);
		require(c != null, "原差异单不存在于当前组织");
		return c;
	}
}
