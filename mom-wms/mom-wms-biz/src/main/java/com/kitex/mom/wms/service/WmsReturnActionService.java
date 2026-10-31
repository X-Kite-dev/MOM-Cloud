package com.kitex.mom.wms.service;

import cn.hutool.json.JSONNull;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.dto.WmsProductionReturnReceiptCommand;
import com.kitex.mom.wms.dto.WmsReturnActionDTO.Command;
import com.kitex.mom.wms.dto.WmsReturnActionDTO.Cancellation;
import com.kitex.mom.wms.dto.WmsReturnActionDTO.View;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.entity.WmsProductionReturnReceiptOperationEntity;
import com.kitex.mom.wms.enums.menu.ReturnReceiptActionEnum;
import com.kitex.mom.wms.mapper.WmsInNoticeMapper;
import com.kitex.mom.wms.mapper.WmsProductionReturnReceiptOperationMapper;
import com.kitex.mom.wms.service.impl.WmsProductionReturnReceiptService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 原通知行锁串行化执行/撤销；不可变操作履历即永久回执，不新增业务状态表。 */
@Service
@RequiredArgsConstructor
public class WmsReturnActionService {
	private final WmsInNoticeMapper notices;
	private final WmsProductionReturnReceiptOperationMapper operations;
	private final WmsProductionReturnReceiptService business;
	private final WmsReturnReceiptAccessService access;

	@Transactional(rollbackFor = Exception.class)
	public View execute(Command input) {
		Command command = canonical(input);
		WmsInNoticeEntity notice = source(command.noticeId(), command.warehouseCode(), command.action(), true);
		var original = find(command.requestId());
		if (original == null) {
			if ("DISPOSITION".equals(command.action())) {
				business.dispose(new WmsProductionReturnReceiptCommand.Disposition().setNoticeId(command.noticeId())
						.setRequestId(command.requestId()).setDecision(command.decision()).setReason(command.reason()));
			} else {
				business.retryDelivery(new WmsProductionReturnReceiptCommand.RetryDelivery().setNoticeId(command.noticeId())
						.setRequestId(command.requestId()).setReason(command.reason()));
			}
			original = find(command.requestId());
		}
		return receipt(original, notice, command);
	}

	public View lookup(String requestId, Long noticeId, String warehouseCode, String action) {
		request(requestId);
		WmsInNoticeEntity notice = source(noticeId, warehouseCode, action, false);
		var original = find(requestId);
		if (original == null) {
			return new View(1, requestId, noticeId, warehouseCode, action, "NOT_CONFIRMED", null, null, null, null);
		}
		Command command = storedCommand(original);
		require(Objects.equals(command.noticeId(), noticeId) && Objects.equals(command.warehouseCode(), warehouseCode)
				&& Objects.equals(command.action(), action), "原操作回执与查询范围不一致");
		return receipt(original, notice, command);
	}

	@Transactional(rollbackFor = Exception.class)
	public View cancel(Cancellation input) {
		require(input != null, "请选择需要核对的原操作");
		Command command = canonical(input.command());
		String reason = text(input.reason());
		require(!reason.isEmpty() && reason.length() <= 500, "请填写500字以内的撤销原因");
		WmsInNoticeEntity notice = source(command.noticeId(), command.warehouseCode(), command.action(), true);
		var original = find(command.requestId());
		if (original == null) {
			var user = SecurityUtils.getUser();
			String status = text(notice.getReturnReceiptStatus());
			if (status.isEmpty()) {
				status = "WAITING";
			}
			String snapshot = JSONUtil.createObj().set("contractVersion", 1)
					.set("command", WmsReturnActionSnapshot.command(notice, command.action(), command.decision(), command.reason()))
					.set("status", "CANCELLED").set("result", JSONNull.NULL).set("cancellationReason", reason).toString();
			var row = new WmsProductionReturnReceiptOperationEntity().setNoticeId(command.noticeId())
					.setRequestId(command.requestId()).setOperationType(command.action()).setBeforeStatus(status).setAfterStatus(status)
					.setFactoryCode(notice.getFactoryCode()).setOperatorCode(user.getUsername())
					.setOperatorName(text(user.getName()).isEmpty() ? user.getUsername() : user.getName())
					.setOperationTime(LocalDateTime.now()).setDetail(snapshot);
			row.setTenantId(notice.getTenantId());
			require(operations.insert(row) == 1, "撤销原操作请求未可靠封存，请继续核对");
			original = find(command.requestId());
		}
		return receipt(original, notice, command);
	}

	private View receipt(WmsProductionReturnReceiptOperationEntity row, WmsInNoticeEntity notice, Command command) {
		require(row != null && row.getOperationTime() != null, "原操作结果未可靠封存，请继续核对");
		var snapshot = WmsReturnActionSnapshot.validateOriginal(row, notice, SecurityUtils.getUser().getUsername(),
				WmsReturnActionSnapshot.command(notice, command.action(), command.decision(), command.reason()));
		String payload = JSONUtil.toJsonStr(payload(command));
		if ("CANCELLED".equals(snapshot.getStr("status"))) {
			Object result = snapshot.get("result");
			String reason = text(snapshot.getStr("cancellationReason"));
			require((result == null || result == JSONNull.NULL) && !reason.isEmpty() && reason.length() <= 500,
					"原撤销回执不完整，请核对操作履历");
			return new View(1, command.requestId(), command.noticeId(), command.warehouseCode(), command.action(),
					"CANCELLED", payload, null, row.getOperationTime(), reason);
		}
		var result = WmsReturnActionSnapshot.replay(row, notice, SecurityUtils.getUser().getUsername(),
				WmsReturnActionSnapshot.command(notice, command.action(), command.decision(), command.reason()));
		result.setCapabilities(access.capabilities(notice));
		return new View(1, command.requestId(), command.noticeId(), command.warehouseCode(), command.action(),
				"CONFIRMED", payload, result, row.getOperationTime(), null);
	}

	private Command storedCommand(WmsProductionReturnReceiptOperationEntity row) {
		try {
			var original = JSONUtil.parseObj(row.getDetail()).getJSONObject("command");
			require(original != null, "历史履历缺少原操作命令，请核对历史记录");
			return canonical(new Command(row.getRequestId(), Long.valueOf(original.getStr("noticeId")),
					original.getStr("warehouseCode"), original.getStr("operationType"), original.getStr("decision"), original.getStr("reason")));
		} catch (CheckedException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new CheckedException("原请求命令无法读取，请核对历史操作履历");
		}
	}

	private WmsProductionReturnReceiptOperationEntity find(String requestId) {
		return operations.selectOne(new LambdaQueryWrapper<WmsProductionReturnReceiptOperationEntity>()
				.eq(WmsProductionReturnReceiptOperationEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(WmsProductionReturnReceiptOperationEntity::getRequestId, requestId));
	}

	private WmsInNoticeEntity source(Long id, String warehouse, String action, boolean lock) {
		ReturnReceiptActionEnum capability = capability(action);
		access.requirePermission(warehouse, capability);
		require(id != null && id > 0, "请选择原生产退料通知");
		var row = notices.selectOne(new LambdaQueryWrapper<WmsInNoticeEntity>().eq(WmsInNoticeEntity::getId, id)
				.eq(WmsInNoticeEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(WmsInNoticeEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
				.eq(WmsInNoticeEntity::getWarehouseCode, warehouse).last(lock ? "FOR UPDATE" : ""));
		access.requireNotice(row, capability);
		return row;
	}

	private ReturnReceiptActionEnum capability(String action) {
		require(action != null && Set.of("DISPOSITION", "DELIVERY_RETRY").contains(action), "退料操作类型无效");
		return "DISPOSITION".equals(action) ? ReturnReceiptActionEnum.DISPOSE : ReturnReceiptActionEnum.RETRY;
	}

	private Command canonical(Command command) {
		require(command != null, "原操作请求不能为空");
		request(command.requestId());
		capability(command.action());
		String decision = text(command.decision());
		String reason = text(command.reason());
		require(command.noticeId() != null && command.noticeId() > 0 && !text(command.warehouseCode()).isEmpty()
				&& command.warehouseCode().equals(command.warehouseCode().trim()) && command.warehouseCode().length() <= 64
				&& !command.warehouseCode().contains("*") && !command.warehouseCode().contains("?")
				&& !reason.isEmpty() && reason.length() <= 500, "原操作通知、仓库或依据不完整");
		if ("DISPOSITION".equals(command.action())) {
			require(Set.of("ACCEPT_SHORTAGE", "CONTINUE_RECEIPT", "RETURN_OVERAGE").contains(decision), "请选择有效处置结论");
		} else {
			require(decision.isEmpty(), "回写续跑不能携带处置结论");
		}
		return new Command(command.requestId(), command.noticeId(), command.warehouseCode(), command.action(), decision, reason);
	}

	private Map<String, String> payload(Command command) {
		Map<String, String> result = new LinkedHashMap<>();
		result.put("noticeId", command.noticeId().toString());
		result.put("warehouseCode", command.warehouseCode());
		result.put("action", command.action());
		result.put("decision", command.decision());
		result.put("reason", command.reason());
		return result;
	}

	private void request(String id) {
		require(id != null && id.matches("[A-Za-z0-9][A-Za-z0-9_-]{15,63}"), "原操作请求号无效");
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
