package com.kitex.mom.wms.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsInventoryRecountCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.number;
import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;

/**
 * 原请求规范化不读取现库存，仍须完整保留原分组和精确数量。
 */
@Component
@RequiredArgsConstructor
public class WmsInventoryRecountRequestCodec {
	private final ObjectMapper json;
	private final WmsInventoryOwnerCountEvidence evidence;

	public String encode(WmsInventoryRecountCommand command) {
		require(command != null && command.getPlanDetailId() != null && command.getPlanDetailId() > 0,
				"请选择原盘点明细");
		require(command.getRequestId() != null && command.getRequestId().matches("[A-Za-z0-9_-]{16,64}"),
				"原复盘请求号无效");
		require(command.getReason() != null && !command.getReason().isBlank() &&
				command.getReason().trim().length() <= 500, "原复盘原因不完整");
		require(command.getRecountNum() == null && command.getOwnerBasisVersion() != null
				&& command.getOwnerBasisVersion().matches("[a-f0-9]{64}"), "原分货权依据不完整");
		var pointer = command.getOwnerExpectedRecountId();
		require(pointer == null || pointer.matches("[1-9][0-9]{0,18}")
						&& new java.math.BigInteger(pointer).compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)) <= 0,
				"原复盘关联无效");
		evidence.proposedTotal(command); // 只验证完整非负原文；结束原请求不执行数量记账。
		var keys = new HashSet<String>();
		var groups = command.getOwnerCounts().stream().map(item -> {
			require(item.key() != null && item.key().matches("[a-f0-9]{64}") && keys.add(item.key()),
					"原分组重复或无效");
			return new WmsInventoryRecountCommand.OwnerCount(item.key(),
					new BigDecimal(item.quantity()).stripTrailingZeros().toPlainString());
		}).sorted(Comparator.comparing(WmsInventoryRecountCommand.OwnerCount::key)).toList();
		var original = new Original(command.getPlanDetailId().toString(), command.getRequestId(),
				command.getReason().trim(),
				number(command.getExpectedInventoryNum(), "原实盘数量").stripTrailingZeros().toPlainString(),
				command.getOwnerBasisVersion(), pointer, groups);
		try {
			return json.writeValueAsString(original);
		} catch (JsonProcessingException ex) {
			throw new CheckedException("保存原复盘依据失败");
		}
	}

	public record Original(String planDetailId, String requestId, String reason, String expectedInventoryNum,
	                       String ownerBasisVersion, String ownerExpectedRecountId,
	                       List<WmsInventoryRecountCommand.OwnerCount> ownerCounts) {
	}
}
