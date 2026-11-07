package com.kitex.mom.wms.service.rework;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.mom.wms.dto.WmsNcrReworkDTO.*;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.WmsMaterialReservationService;
import com.kitex.mom.wms.service.WmsSalesStockReservationService;
import com.kitex.mom.wms.service.special.WmsSpecialStockProof;
import com.kitex.mom.wms.service.special.WmsSpecialReturnQualityProofService;
import com.kitex.mom.wms.util.WmsSerialStockGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

import static com.kitex.mom.wms.service.rework.WmsNcrReworkRules.*;

/**
 * 原NCR/root/MRB/parent、永久拆分谱系及当前精确片段证明。
 * 冻结源历史读取不依赖今日库存；首次办理另按ID升序锁定并重建全文。
 */
@Component
@RequiredArgsConstructor
public class WmsNcrReworkProof {
	private final WmsQualityStatusOperationMapper operations;
	private final WmsLiveStockMapper stocks;
	private final WmsMaterialReservationService reservations;
	private final WmsSalesStockReservationService salesReservations;
	private final WmsSpecialReturnQualityProofService special;
	private final ObjectMapper json;

	/**
	 * 原parent和root，不使用最新同批记录或客户端仓下拉值推断。
	 */
	public record Parents(WmsQualityStatusOperationEntity root, WmsQualityStatusOperationEntity parent) {
	}

	/**
	 * 精确加载原parent；写事务必须先原号/特殊仓锁，再锁parent，后锁库存。
	 */
	public Parents parents(Key k, boolean lock) {
		var q = scope(k).eq(WmsQualityStatusOperationEntity::getId, id(k.parentOperationId()));
		if (lock) {
			q.last("FOR UPDATE");
		}
		var p = operations.selectOne(q, false);
		var r = operations.selectOne(
				scope(k).eq(WmsQualityStatusOperationEntity::getId, id(k.rootHoldOperationId())), false);
		require(p != null && r != null && !p.getId().equals(r.getId()), "原返工parent或首次隔离操作不存在");
		for (var op : List.of(r, p)) {
			require("QMS_NCR".equals(op.getSourceType()) && Objects.equals(k.ncrId(), op.getSourceId())
							&& Objects.equals(k.holdNo(), op.getHoldNo()) && "SUCCESS".equals(op.getStatus()),
					"原返工与首次隔离不属于同一已成功NCR");
			links(op);
		}
		require("HOLD".equals(r.getAction()) && "REWORK".equals(p.getAction())
						&& ("WMS-MRB:" + k.proposalId()).equals(p.getRequestId())
						&& p.getRequestedQuantity() != null && p.getAffectedQuantity() != null
						&& p.getRequestedQuantity().compareTo(quantity(k.quantity())) == 0
						&& p.getAffectedQuantity().compareTo(quantity(k.quantity())) == 0,
				"MRB原号、返工状态或整量不匹配");
		require(r.getRequestedQuantity() != null && r.getAffectedQuantity() != null
						&& r.getRequestedQuantity().compareTo(r.getAffectedQuantity()) == 0
						&& r.getAffectedQuantity().compareTo(p.getRequestedQuantity()) >= 0,
				"原始隔离整量不完整或少于本次返工量");
		require(r.getSplitRecordedAt().compareTo(p.getSplitRecordedAt()) <= 0, "返工早于首次隔离");
		if (k.binding() == null) {
			require(r.getSpecialReturnBindingJson() == null && p.getSpecialReturnBindingJson() == null,
					"特殊原件不能去除Binding退回普通返工");
		} else {
			special.validateNcrReworkBinding(k.binding(), r, p);
		}
		lineage(k, r, p);
		return new Parents(r, p);
	}

	/**
	 * 冻结来源只核永久parent与逐件归属，可供lookup/cancel在库存已出库时使用。
	 */
	public Parents frozen(Source source, boolean lock) {
		source(source);
		var pair = parents(source.key(), lock);
		var p = pair.parent();
		Set<String> affected = ids(p.getAffectedStockIds());
		require(affected.equals(new LinkedHashSet<>(source.pieces().stream().map(Piece::stockId).toList())),
				"冻结来源不是原返工精确影响全集");
		for (Piece piece : source.pieces()) {
			for (var op : List.of(pair.root(), p)) {
				equalFrozen(op.getMatCode(), piece.identity().get("matCode"));
				equalFrozen(op.getMatBatch(), piece.identity().get("matBatch"));
				equalFrozen(op.getMatUnit(), source.key().unit());
				equalFrozen(op.getSupplierCode(), piece.identity().get("supplierCode"));
				equalFrozen(op.getWarehouseCode(), source.warehouseCode());
				equalFrozen(op.getSiteCode(), piece.siteCode());
				equalFrozen(op.getContainerCode(), piece.containerCode());
			}
			require("REWORK".equals(piece.identity().get("qualityStatus"))
							&& "UN_QUALIFIED".equals(piece.identity().get("qualityResult"))
							&& source.key().holdNo().equals(piece.identity().get("qualityHoldNo"))
							&& "QMS_NCR".equals(piece.identity().get("qualitySourceType"))
							&& source.key().ncrId().equals(piece.identity().get("qualitySourceId")),
					"冻结原件质量来源不匹配");
			if (source.key().binding() != null) {
				special.validateNcrReworkPiece(source.key().binding().target(), piece.identity(),
						source.warehouseCode(), piece.siteCode(), piece.containerCode());
			}
		}
		return pair;
	}

	/**
	 * 当前库存按永久影响ID全集读取；仅首次apply加锁，不从root全集补足数量。
	 */
	public Source inspect(Key key, Parents pair, boolean lock) {
		Set<String> affected = ids(pair.parent().getAffectedStockIds());
		require(!affected.isEmpty(), "原返工缺少受影响库存ID");
		var query = new LambdaQueryWrapper<WmsLiveStockEntity>()
				.eq(WmsLiveStockEntity::getTenantId, id(key.tenantId()))
				.eq(WmsLiveStockEntity::getFactoryCode, key.factoryCode())
				.in(WmsLiveStockEntity::getId, affected.stream().map(WmsNcrReworkRules::id).toList())
				.orderByAsc(WmsLiveStockEntity::getId);
		if (lock) {
			query.last("FOR UPDATE");
		}
		var rows = stocks.selectList(query);
		require(rows.size() == affected.size(), "原返工精确片段已归档或缺失");
		var pieces = new ArrayList<Piece>();
		Set<String> units = new HashSet<>();
		Set<String> warehouses = new HashSet<>();
		var sales = salesReservations.activeReservedNums(
				new HashSet<>(rows.stream().map(WmsLiveStockEntity::getId).toList()));
		for (var stock : rows) {
			require(Integer.valueOf(1).equals(stock.getStatus()) && "REWORK".equals(stock.getQualityStatus())
							&& "UN_QUALIFIED".equals(stock.getQualityResult())
							&& key.holdNo().equals(stock.getQualityHoldNo())
							&& "QMS_NCR".equals(stock.getQualitySourceType()) &&
							key.ncrId().equals(stock.getQualitySourceId()),
					"原片段已变化或不再属于本次返工");
			require(reservations.activeReservedNum(stock.getId()).signum() == 0
							&& sales.getOrDefault(stock.getId(), BigDecimal.ZERO).signum() == 0,
					"原返工片段存在生产或销售有效预留");
			BigDecimal amount = amount(stock);
			WmsSerialStockGuard.requireWhole(stock, amount);
			if (stock.getQualityReworkOperationId() == null) {
				legacy(key, pair, stock, rows.size());
			} else {
				require(Objects.equals(stock.getQualityReworkOperationId(), pair.parent().getId()),
						"库存属于另一轮返工parent，禁止同案同ID重入");
			}
			field(stock.getMatUnit(), 100, true);
			field(stock.getWarehouseCode(), 100, true);
			units.add(stock.getMatUnit());
			warehouses.add(stock.getWarehouseCode());
			pieces.add(new Piece(stock.getId().toString(), amount.stripTrailingZeros().toPlainString(),
					stock.getWarehouseCode(), text(stock.getSiteCode()), text(stock.getContainerCode()),
					WmsSpecialStockProof.identity(stock)));
		}
		require(units.size() == 1 && warehouses.size() == 1, "返工片段必须同一实际仓和同一非空基本单位");
		String unit = units.iterator().next();
		require(key.unit() == null || unit.equals(key.unit()), "返工原显式单位与真实片段不一致");
		Key actual = new Key(key.tenantId(), key.factoryCode(), key.ncrId(), key.detailId(), key.detailNo(),
				key.proposalId(),
				key.rootHoldOperationId(), key.parentOperationId(), key.holdNo(), key.quantity(), unit,
				key.binding());
		Source source = new Source(1, actual, warehouses.iterator().next(), List.copyOf(pieces));
		frozen(source, false);
		return source;
	}

	/**
	 * 旧无owner只接受单件、整量、同案没有第三次触及记录的完整永久证据。
	 */
	private void legacy(Key key, Parents pair, WmsLiveStockEntity stock, int size) {
		require(size == 1 && amount(stock).compareTo(quantity(key.quantity())) == 0,
				"历史返工无所有者，仅支持单库存整量且完整证据的人工核对范围");
		for (var op : caseOperations(key)) {
			if (op.getId().equals(pair.root().getId()) || op.getId().equals(pair.parent().getId()) ||
					!done(op)) {
				continue;
			}
			Set<String> touched = ids(op.getAffectedStockIds());
			for (var link : links(op)) {
				touched.add(link.get("parentStockId"));
				touched.add(link.get("remainderStockId"));
			}
			require(!touched.contains(stock.getId().toString()),
					"历史同案同ID存在第三次办理，无法排除ABA，需人工治理");
		}
	}

	/**
	 * 仅扩展同案已成功、发生在parent之前的永久拆分后裔；root余量不属于本案。
	 */
	private void lineage(Key k, WmsQualityStatusOperationEntity root, WmsQualityStatusOperationEntity parent) {
		Set<String> allowed = ids(root.getAffectedStockIds());
		require(!allowed.isEmpty(), "首次隔离缺少原件ID");
		var all = caseOperations(k);
		all.sort(Comparator.comparing(WmsQualityStatusOperationEntity::getSplitRecordedAt,
				Comparator.nullsLast(Comparator.naturalOrder())));
		for (var op : all) {
			if (op.getId().equals(root.getId()) || !done(op)) {
				continue;
			}
			links(op);
			if (op.getSplitRecordedAt().isBefore(root.getSplitRecordedAt()) ||
					op.getSplitRecordedAt().isAfter(parent.getSplitRecordedAt())) {
				continue;
			}
			require(!"HOLD".equals(op.getAction()), "同案存在另一次隔离，无法唯一证明原root");
			if (k.binding() != null) {
				var binding = special.decode(op.getSpecialReturnBindingJson());
				require(binding != null && k.rootHoldOperationId().equals(binding.rootHoldOperationId())
						&& k.binding().target().equals(binding.target()), "同案特殊拆分不属于原root");
			}
			require(allowed.containsAll(ids(op.getAffectedStockIds())), "处置影响ID无法沿原root永久拆分证明");
			if (op.getId().equals(parent.getId())) {
				continue;
			}
			for (var link : links(op)) {
				require(allowed.contains(link.get("parentStockId")), "拆分父片段不属于原root");
				allowed.add(link.get("remainderStockId"));
			}
		}
		require(allowed.containsAll(ids(parent.getAffectedStockIds())), "本次parent不属于原root精确后裔");
	}

	/**
	 * 原位更新后核完整物理身份及位置，只放行明确质量三元组变化。
	 */
	public void verifyAfter(Source original, List<Change> changes) {
		for (int i = 0; i < original.pieces().size(); i++) {
			var p = original.pieces().get(i);
			var c = changes.get(i);
			var stock = stocks.selectOne(new LambdaQueryWrapper<WmsLiveStockEntity>()
					.eq(WmsLiveStockEntity::getTenantId, id(original.key().tenantId()))
					.eq(WmsLiveStockEntity::getFactoryCode, original.key().factoryCode())
					.eq(WmsLiveStockEntity::getId, id(p.stockId())), false);
			require(stock != null && stock.getQualityReworkOperationId() == null
					&& p.warehouseCode().equals(stock.getWarehouseCode())
					&& p.siteCode().equals(text(stock.getSiteCode()))
					&& p.containerCode().equals(text(stock.getContainerCode()))
					&& amount(stock).compareTo(quantity(p.quantity())) == 0, "返工回读位置、owner或原量不一致");
			var identity = new TreeMap<>(WmsSpecialStockProof.identity(stock));
			identity.put("qualityStatus", c.beforeQuality());
			identity.put("qualityResult", c.beforeResult());
			require(identity.equals(p.identity()), "返工原位更新意外改变物理身份或首次来源");
		}
	}

	/**
	 * 新普通REWORK只消耗唯一首次HOLD的可证后裔，不把同案同批另一隔离或已返工片段抢走。
	 * 此方法在质量引擎已经锁定候选库存后调用；所有历史拆分证据不完整均拒绝，不补造。
	 */
	public void requireNewOrdinaryOrigin(WmsQualityStatusOperationEntity pending,
	                                     List<WmsLiveStockEntity> held) {
		var all = operations.selectList(new LambdaQueryWrapper<WmsQualityStatusOperationEntity>()
				.eq(WmsQualityStatusOperationEntity::getTenantId, pending.getTenantId())
				.eq(WmsQualityStatusOperationEntity::getFactoryCode, pending.getFactoryCode())
				.eq(WmsQualityStatusOperationEntity::getSourceType, "QMS_NCR")
				.eq(WmsQualityStatusOperationEntity::getSourceId, pending.getSourceId())
				.eq(WmsQualityStatusOperationEntity::getHoldNo, pending.getHoldNo()).last("LIMIT 10001"));
		require(all.size() <= 10000, "原案来源证明超限");
		var roots = all.stream().filter(o -> "HOLD".equals(o.getAction()) && "SUCCESS".equals(o.getStatus()))
				.toList();
		require(roots.size() == 1 && roots.get(0).getSpecialReturnBindingJson() == null,
				"普通返工缺少唯一原始HOLD，复杂历史需人工治理");
		var root = roots.get(0);
		links(root);
		Set<String> allowed = ids(root.getAffectedStockIds());
		all.sort(Comparator.comparing(WmsQualityStatusOperationEntity::getSplitRecordedAt,
				Comparator.nullsLast(Comparator.naturalOrder())));
		for (var op : all) {
			if (op.getId().equals(root.getId()) || !done(op)) {
				continue;
			}
			require(op.getSpecialReturnBindingJson() == null, "普通原案存在特殊来源混用");
			require(allowed.containsAll(ids(op.getAffectedStockIds())), "同案历史处置不属于原始隔离片段");
			for (var link : links(op)) {
				require(allowed.contains(link.get("parentStockId")), "返工候选拆分父片段无法证明");
				allowed.add(link.get("remainderStockId"));
			}
		}
		for (var stock : held) {
			require(allowed.contains(stock.getId().toString()) && stock.getQualityReworkOperationId() == null
					&& "HOLD".equals(stock.getQualityStatus()), "新返工只能消耗原root尚未办理的HOLD");
		}
	}

	private List<WmsQualityStatusOperationEntity> caseOperations(Key k) {
		var rows = operations.selectList(scope(k).eq(WmsQualityStatusOperationEntity::getSourceType, "QMS_NCR")
				.eq(WmsQualityStatusOperationEntity::getSourceId, k.ncrId())
				.eq(WmsQualityStatusOperationEntity::getHoldNo, k.holdNo()).last("LIMIT 10001"));
		require(rows.size() <= 10000, "原案永久操作证据超限，需人工治理");
		return new ArrayList<>(rows);
	}

	private LambdaQueryWrapper<WmsQualityStatusOperationEntity> scope(Key k) {
		return new LambdaQueryWrapper<WmsQualityStatusOperationEntity>()
				.eq(WmsQualityStatusOperationEntity::getTenantId, id(k.tenantId()))
				.eq(WmsQualityStatusOperationEntity::getFactoryCode, k.factoryCode());
	}

	/**
	 * 拆分记录完整、ID去重、映射与余量全集严格相等，拒绝部分历史或猜测。
	 */
	private List<Map<String, String>> links(WmsQualityStatusOperationEntity op) {
		require(Boolean.TRUE.equals(op.getSplitRecorded()) && op.getSplitRecordedAt() != null
				&& op.getSplitLineageJson() != null, "原操作缺少完整永久拆分证明，需人工治理");
		try {
			List<Map<String, String>> links = json.readValue(op.getSplitLineageJson(), new TypeReference<>() {
			});
			Set<String> remainders = new LinkedHashSet<>();
			Set<String> affected = ids(op.getAffectedStockIds());
			for (var link : links) {
				id(link.get("parentStockId"));
				id(link.get("remainderStockId"));
				quantity(link.get("remainderQuantity"));
				require(link.size() == 3 && affected.contains(link.get("parentStockId"))
								&& !affected.contains(link.get("remainderStockId")) &&
								remainders.add(link.get("remainderStockId")),
						"永久拆分父ID或余量身份无效");
			}
			require(remainders.equals(ids(op.getSplitRemainderStockIds())), "永久拆分余量全集不匹配");
			return links;
		} catch (com.kitex.common.core.exception.CheckedException e) {
			throw e;
		} catch (Exception e) {
			throw new com.kitex.common.core.exception.CheckedException("永久拆分原文不可读取");
		}
	}

	private Set<String> ids(String value) {
		Set<String> result = new LinkedHashSet<>();
		if (value != null && !value.isBlank()) {
			for (String item : value.split(",", -1)) {
				id(item);
				require(result.add(item), "永久操作库存ID重复");
			}
		}
		return result;
	}

	private boolean done(WmsQualityStatusOperationEntity op) {
		return Set.of("SUCCESS", "ACTION_PENDING", "EXECUTED").contains(op.getStatus());
	}

	private void equalFrozen(String frozen, String actual) {
		require(frozen == null || frozen.isBlank() || Objects.equals(frozen, actual),
				"原操作非空冻结身份或位置已漂移");
	}

	/**
	 * Double库存量必须有限、正且满足返工精度；实际写入不改变此数量。
	 */
	public static BigDecimal amount(WmsLiveStockEntity stock) {
		require(stock.getMatNum() != null && Double.isFinite(stock.getMatNum()) && stock.getMatNum() > 0,
				"原库存量不是有效正数量");
		BigDecimal n = BigDecimal.valueOf(stock.getMatNum()).stripTrailingZeros();
		quantity(n.toPlainString());
		return n;
	}

	private String text(String value) {
		return value == null ? "" : value;
	}
}
