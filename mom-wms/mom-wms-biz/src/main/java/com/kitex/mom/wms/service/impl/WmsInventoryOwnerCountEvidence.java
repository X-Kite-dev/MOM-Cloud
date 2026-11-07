package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsInventoryRecountCommand;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;
import com.kitex.mom.wms.mapper.WmsInventoryVariancePolicyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.factory;
import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.number;
import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;

/**
 * 分货权实盘只描述观察事实，不将货主差异自动分摊或结算。
 */
@Service
@RequiredArgsConstructor
public class WmsInventoryOwnerCountEvidence {
	private final WmsInventoryVariancePolicyMapper records;
	private final ObjectMapper json;

	public String basisVersion(WmsInsideInventoryPlanDetailEntity row,
	                           List<WmsInventoryStockCheckService.Basis> basis) {
		var parts = new ArrayList<String>();
		parts.addAll(Arrays.asList(String.valueOf(row.getTenantId()), row.getFactoryCode(), String.valueOf(row.getId()),
				row.getWarehouseCode(), row.getMatCode(), row.getSiteCode(), StrUtil.emptyToNull(row.getMatBatch()),
				row.getMatUnit(), decimal(number(row.getCurrentNumber(), "原账面数量"))));
		basis.stream().sorted(Comparator.comparing(b -> new java.math.BigInteger(b.stockId()))).forEach(b ->
				parts.addAll(Arrays.asList(b.stockId(), b.ownerType(), b.ownerCode(), b.sourceBusinessNo(), b.matUnit(),
						b.quantity())));
		return hash(parts);
	}

	public List<Group> groups(WmsInsideInventoryPlanDetailEntity row,
	                          List<WmsInventoryStockCheckService.Balance> balances) {
		return balances.stream()
				.map(b -> new Group(hash(Arrays.asList(String.valueOf(row.getTenantId()), row.getFactoryCode(),
						String.valueOf(row.getId()), b.ownerType(), b.ownerCode(), b.sourceBusinessNo(), b.matUnit())),
						b.ownerType(), b.ownerCode(), b.sourceBusinessNo(), b.matUnit(), b.quantity(), null, null))
				.sorted(Comparator.comparing(Group::key)).toList();
	}

	public Capture capture(WmsInsideInventoryPlanDetailEntity row, WmsInventoryStockCheckService.Check stock,
	                       WmsInventoryRecountCommand command) {
		require(command.getOwnerCounts() != null && command.getRecountNum() == null,
				"分货权复盘请逐组填写数量，不提交单独合计");
		require(Objects.equals(command.getOwnerExpectedRecountId(), id(row.getLastRecountId())),
				"明细已被其他复盘更新，请重新核对");
		require(Objects.equals(command.getOwnerBasisVersion(), basisVersion(row, stock.basis())),
				"库存依据已变化，请重新核对后记录");
		require(Objects.equals(stock.bookQuantity(), stock.liveQuantity())
						&& !List.of("UNIT_MISMATCH", "INVALID_OWNERSHIP", "STOCK_CHANGED").contains(stock.status()),
				"库存数量、货权或单位与原账面不一致，不能记录本次货权复盘");
		List<Group> counted = count(groups(row, stock.balances()), command.getOwnerCounts());
		BigDecimal total = counted.stream().map(g -> quantity(g.countedQuantity()))
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		require(Double.isFinite(total.doubleValue()) && total.compareTo(BigDecimal.valueOf(total.doubleValue())) == 0,
				"合计数量超出当前盘点精度，请核对实盘数量");
		Snapshot snapshot = new Snapshot(1, command.getOwnerBasisVersion(), command.getOwnerExpectedRecountId(),
				decimal(number(command.getExpectedInventoryNum(), "原实盘数量")), decimal(total), counted,
				stock.basis());
		try {
			return new Capture(json.writeValueAsString(snapshot), total);
		} catch (JsonProcessingException ex) {
			throw new CheckedException("保存分货权复盘依据失败");
		}
	}

	public BigDecimal proposedTotal(WmsInventoryRecountCommand command) {
		require(command.getOwnerCounts() != null && !command.getOwnerCounts().isEmpty(), "请完整填写各组实盘数量");
		return command.getOwnerCounts().stream().map(c -> {
					require(c != null, "实盘分组无效");
					return quantity(c.quantity());
				})
				.reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	public boolean matchesOriginal(String frozen, WmsInventoryRecountCommand command) {
		if (command.getOwnerCounts() == null) {
			return frozen == null;
		}
		if (frozen == null) {
			return false;
		}
		try {
			Snapshot snapshot = json.readValue(frozen, Snapshot.class);
			return snapshot.version() == 1 && Objects.equals(snapshot.basisVersion(), command.getOwnerBasisVersion())
					&& Objects.equals(snapshot.expectedRecountId(), command.getOwnerExpectedRecountId())
					&& snapshot.groups().equals(count(snapshot.groups(), command.getOwnerCounts()));
		} catch (RuntimeException | JsonProcessingException ex) {
			return false;
		}
	}

	public Review review(WmsInsideInventoryPlanDetailEntity row, List<WmsInventoryStockCheckService.Basis> basis,
	                     List<WmsInventoryStockCheckService.Balance> balances) {
		boolean external = basis.stream().anyMatch(b -> !"OWNED".equals(b.ownerType()));
		if (row.getLastRecountId() == null) {
			return new Review(external ? "MISSING" : "NOT_REQUIRED", null, List.of());
		}
		var record = records.recountById(row.getTenantId(), factory(), row.getId(), row.getLastRecountId());
		if (record == null || !Objects.equals(record.getTenantId(), row.getTenantId())
				|| !Objects.equals(record.getFactoryCode(), row.getFactoryCode()) ||
				!Objects.equals(record.getPlanId(), row.getPlanId())
				|| !Objects.equals(record.getPlanDetailId(), row.getId()) ||
				!Objects.equals(record.getId(), row.getLastRecountId())
				|| StrUtil.isBlank(record.getRequestId()) || StrUtil.isBlank(record.getOperatorCode())
				|| StrUtil.isBlank(record.getReason()) || record.getOperatedTime() == null) {
			return new Review("STALE", null, List.of());
		}
		if (record.getOwnerCountJson() == null) {
			return new Review(external ? "MISSING" : "NOT_REQUIRED", id(record.getId()), List.of());
		}
		try {
			Snapshot snapshot = json.readValue(record.getOwnerCountJson(), Snapshot.class);
			var inputs = snapshot.groups().stream()
					.map(g -> new WmsInventoryRecountCommand.OwnerCount(g.key(), g.countedQuantity())).toList();
			List<Group> expected = count(groups(row, balances), inputs);
			BigDecimal total = expected.stream().map(g -> quantity(g.countedQuantity()))
					.reduce(BigDecimal.ZERO, BigDecimal::add);
			require(snapshot.version() == 1 && Objects.equals(snapshot.basisVersion(), basisVersion(row, basis))
							&& Objects.equals(snapshot.basis(), basis) && Objects.equals(snapshot.groups(), expected)
							&& Objects.equals(snapshot.total(), decimal(total))
							&& total.compareTo(number(row.getInventoryNum(), "当前实盘数量")) == 0
							&& total.compareTo(number(record.getRecountNum(), "复盘实盘数量")) == 0,
					"分货权复盘依据已失效");
			boolean balanced = expected.stream().allMatch(g -> "0".equals(g.difference()));
			return new Review(balanced ? "BALANCED" : "DIFFERENCE", id(record.getId()), expected);
		} catch (RuntimeException | JsonProcessingException ex) {
			return new Review("STALE", id(record.getId()), List.of());
		}
	}

	private List<Group> count(List<Group> expected, List<WmsInventoryRecountCommand.OwnerCount> inputs) {
		require(!expected.isEmpty() && inputs != null && inputs.size() == expected.size(), "请完整填写所有货权分组");
		Map<String, String> values = new HashMap<>();
		for (var item : inputs) {
			require(item != null && item.key() != null && item.key().matches("[a-f0-9]{64}")
							&& values.putIfAbsent(item.key(), decimal(quantity(item.quantity()))) == null,
					"货权分组重复或无效");
		}
		return expected.stream().map(g -> {
			require(values.containsKey(g.key()), "货权分组与原库存不一致");
			String counted = values.get(g.key());
			return new Group(g.key(), g.ownerType(), g.ownerCode(), g.sourceBusinessNo(), g.matUnit(), g.bookQuantity(),
					counted,
					decimal(quantity(counted).subtract(quantity(g.bookQuantity()))));
		}).sorted(Comparator.comparing(Group::key)).toList();
	}

	private BigDecimal quantity(String value) {
		require(value != null && value.length() <= 350 && value.matches("[0-9]+(?:\\.[0-9]+)?"),
				"请填写有效的非负实盘数量");
		return new BigDecimal(value);
	}

	private String hash(List<String> parts) {
		StringBuilder text = new StringBuilder();
		parts.forEach(p -> text.append(p == null ? "-1:" : p.length() + ":" + p));
		try {
			return java.util.HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

	private String decimal(BigDecimal value) {
		return value.stripTrailingZeros().toPlainString();
	}

	private String id(Long value) {
		return value == null ? null : value.toString();
	}

	public record Group(String key, String ownerType, String ownerCode, String sourceBusinessNo, String matUnit,
	                    String bookQuantity, String countedQuantity, String difference) {
	}

	public record Review(String status, String recordId, List<Group> groups) {
	}

	public record Snapshot(int version, String basisVersion, String expectedRecountId, String expectedInventoryNum,
	                       String total, List<Group> groups, List<WmsInventoryStockCheckService.Basis> basis) {
	}

	public record Capture(String json, BigDecimal total) {
	}
}
