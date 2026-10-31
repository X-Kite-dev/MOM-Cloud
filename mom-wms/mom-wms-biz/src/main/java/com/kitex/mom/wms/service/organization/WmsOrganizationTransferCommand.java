package com.kitex.mom.wms.service.organization;

import cn.hutool.json.JSONUtil;
import com.kitex.mom.wms.dto.WmsOrganizationTransferDTO.*;

import java.util.*;

import static com.kitex.mom.wms.service.organization.WmsOrganizationTransferRules.require;

/**
 * 请求指纹只包含业务输入，长整型标识明确按字符串输出，避免端间精度丢失。
 */
public final class WmsOrganizationTransferCommand {
	private WmsOrganizationTransferCommand() {
	}

	public static String payload(Command command) {
		require(command != null && command.getAction() != null && command.getRequestId() != null
				&& command.getRequestId().matches("[A-Za-z0-9][A-Za-z0-9_-]{15,63}"), "原操作请求号无效");
		require(command.getTenantId() != null && command.getTenantId() > 0 && command.getOperatorId() != null &&
				command.getOperatorId() > 0 && !text(command.getFactoryCode()).isEmpty(), "请重新准备实际组织身份");
		boolean create = command.getAction() == Action.CREATE;
		require(create ?
						command.getBillId() == null && command.getTargetPolicyId() != null && command.getTargetPolicyId() > 0
						: command.getBillId() != null && command.getBillId() > 0 && command.getTargetPolicyId() == null,
				"组织调拨单据或合作仓库无效");
		require(command.getExpectedVersion() != null &&
						(create ? command.getExpectedVersion() == 0 : command.getExpectedVersion() > 0),
				"请重新读取当前单据版本");
		require(text(command.getAgreementRef()).length() <= 200 && text(command.getReason()).length() <= 1000
				&& text(command.getEvidence()).length() <= 1000, "业务依据、原因或交接凭证内容过长");
		Map<String, Object> packet = new LinkedHashMap<>();
		packet.put("tenantId", id(command.getTenantId()));
		packet.put("operatorId", id(command.getOperatorId()));
		packet.put("factoryCode", text(command.getFactoryCode()));
		packet.put("action", command.getAction().name());
		packet.put("billId", id(command.getBillId()));
		packet.put("warehouseCode", text(command.getWarehouseCode()));
		packet.put("expectedVersion", command.getExpectedVersion());
		packet.put("targetPolicyId", id(command.getTargetPolicyId()));
		packet.put("agreementRef", text(command.getAgreementRef()));
		packet.put("reason", text(command.getReason()));
		packet.put("evidence", text(command.getEvidence()));
		List<Map<String, Object>> lines = new ArrayList<>();
		Set<Long> ids = new HashSet<>();
		var requested = command.getLines() == null ? List.<InputLine>of() : command.getLines();
		boolean physical = Set.of(Action.CREATE, Action.SHIP, Action.RECEIVE, Action.RETURN).contains(
				command.getAction());
		require(physical ? !requested.isEmpty() && requested.size() <= 500 : requested.isEmpty(),
				"本次动作明细不完整或不应包含数量明细");
		for (var input : requested) {
			require(input != null &&
							(create ? input.getStockId() != null && input.getStockId() > 0 && input.getLineId() == null
									: input.getLineId() != null && input.getLineId() > 0 && input.getStockId() == null),
					"请选择有效且不重复的实物明细");
			require(ids.add(create ? input.getStockId() : input.getLineId()), "组织调拨明细不能重复");
			WmsOrganizationTransferStockService.quantity(input.getQuantity());
			require(text(input.getSiteCode()).length() <= 100 && text(input.getContainerCode()).length() <= 100 &&
							text(input.getBarcode()).length() <= 200,
					"实物库位、容器或条码内容过长");
			Map<String, Object> line = new LinkedHashMap<>();
			line.put("lineId", id(input.getLineId()));
			line.put("stockId", id(input.getStockId()));
			line.put("quantity", input.getQuantity());
			line.put("siteCode", text(input.getSiteCode()));
			line.put("containerCode", text(input.getContainerCode()));
			line.put("barcode", text(input.getBarcode()));
			lines.add(line);
		}
		lines.sort(
				Comparator.comparing(line -> (line.get("lineId").toString() + ":" + line.get("stockId")).toString()));
		packet.put("lines", lines);
		return JSONUtil.toJsonStr(packet);
	}

	public static String text(String value) {
		return value == null ? "" : value.trim();
	}

	private static String id(Long value) {
		return value == null ? "" : value.toString();
	}
}
