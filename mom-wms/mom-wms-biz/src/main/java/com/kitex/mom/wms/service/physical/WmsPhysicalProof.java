package com.kitex.mom.wms.service.physical;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.mom.wms.dto.WmsQualityPhysicalDTO.*;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.*;
import com.kitex.mom.wms.service.special.*;
import com.kitex.mom.wms.util.WmsSerialStockGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

import static com.kitex.mom.wms.service.physical.WmsPhysicalRules.*;

/**
 * 精确原operation全集、普通ABA owner、395原件与双预留证明。
 */
@Component
@RequiredArgsConstructor
public class WmsPhysicalProof {
	private final WmsQualityStatusOperationMapper operations;
	private final WmsLiveStockMapper stocks;
	private final WmsMaterialReservationService reservations;
	private final WmsSalesStockReservationService sales;
	private final WmsSpecialReturnQualityProofService special;
	private final WmsPhysicalJson json;

	/**
	 * affected ID不可去重补救、不可trim修正，永久全集数值升序。
	 */
	public List<Long> ids(String csv) {
		require(csv != null && !csv.isBlank(), "原操作缺少精确影响库存全集");
		List<Long> result = Arrays.stream(csv.split(",", -1)).map(WmsPhysicalRules::id).sorted().toList();
		require(new HashSet<>(result).size() == result.size(), "原影响库存ID重复");
		return result;
	}

	/**
	 * 从原持久operation组装不可变Key；原指定字段为空时保留null。
	 */
	public Key key(WmsQualityStatusOperationEntity o) {
		return new Key(o.getTenantId().toString(), o.getFactoryCode(), o.getId().toString(), o.getRequestId(),
				o.getSourceType(), o.getSourceId(), o.getHoldNo(), o.getAction(), o.getMatCode(),
				text(o.getMatBatch()), text(o.getMatUnit()), text(o.getSupplierCode()),
				text(o.getWarehouseCode()), text(o.getSiteCode()), text(o.getContainerCode()),
				amount(o.getRequestedQuantity()), amount(o.getAffectedQuantity()),
				special.decode(o.getSpecialReturnBindingJson()));
	}

	/**
	 * 非当前实物核验，用于取消和原号恢复；不要求今日live仍存在。
	 */
	public void parent(Source source, WmsQualityStatusOperationEntity o) {
		source(source);
		require(source.key().equals(key(o)), "冻结父身份与原处置不一致");
		require(source.pieces().stream().map(p -> id(p.stockId())).toList()
				.equals(ids(o.getAffectedStockIds())), "冻结片段不是原操作影响全集");
		if (o.getPhysicalSourceJson() != null) {
			require(source.equals(json.decode(o.getPhysicalSourceJson(), Source.class)),
					"完整冻结来源与永久分类原件不一致");
		}
	}

	/**
	 * 当前实存仅按原ID全集取得，首次办理升序锁；只读详情不取写锁。
	 */
	public Source inspect(WmsQualityStatusOperationEntity o, boolean lock, boolean classify) {
		require("ACTION_PENDING".equals(o.getStatus()), "原操作不在待实物执行状态");
		var q = new LambdaQueryWrapper<WmsLiveStockEntity>().eq(WmsLiveStockEntity::getTenantId,
						o.getTenantId()).eq(WmsLiveStockEntity::getFactoryCode, o.getFactoryCode())
				.in(WmsLiveStockEntity::getId, ids(o.getAffectedStockIds()))
				.orderByAsc(WmsLiveStockEntity::getId);
		if (lock) {
			q.last("FOR UPDATE");
		}
		List<WmsLiveStockEntity> rows = stocks.selectList(q);
		require(rows.size() == ids(o.getAffectedStockIds()).size(), "原实物片段缺失或已归档");
		var reserved = sales.activeReservedNums(
				new HashSet<>(rows.stream().map(WmsLiveStockEntity::getId).toList()));
		List<Piece> pieces = new ArrayList<>();
		for (var s : rows) {
			require(Integer.valueOf(1).equals(s.getStatus()) &&
					(o.getAction() + "_PENDING").equals(s.getQualityStatus()) &&
					Objects.equals(o.getHoldNo(), s.getQualityHoldNo()) &&
					Objects.equals(o.getSourceType(), s.getQualitySourceType()) &&
					Objects.equals(o.getSourceId(), s.getQualitySourceId()) &&
					s.getQualityReworkOperationId() == null, "原实物状态、来源或返工所有权已变化");
			require(s.getMatNum() != null && Double.isFinite(s.getMatNum()), "真实库存数量缺失或非法");
			BigDecimal n = BigDecimal.valueOf(s.getMatNum());
			quantity(amount(n));
			WmsSerialStockGuard.requireWhole(s, n);
			require(reservations.activeReservedNum(s.getId()).signum() == 0 &&
					reserved.getOrDefault(s.getId(), BigDecimal.ZERO).signum() == 0, "原件存在生产或销售预留");
			if (!classify && o.getSpecialReturnBindingJson() == null &&
					s.getQualityPhysicalOperationId() == null) {
				legacy(o, s, rows.size());
			} else {
				require(Objects.equals(s.getQualityPhysicalOperationId(), o.getId()) ||
								(!classify && o.getSpecialReturnBindingJson() != null &&
										s.getQualityPhysicalOperationId() == null),
						"实物属于另一轮处置，禁止同案同ID重入");
			}
			Map<String, String> identity = WmsSpecialStockProof.identity(s);
			for (String[] f : List.of(new String[]{
							o.getMatCode(), s.getMatCode()}
					, new String[]{
							o.getMatBatch(), s.getMatBatch()}
					, new String[]{
							o.getMatUnit(), s.getMatUnit()}
					, new String[]{
							o.getSupplierCode(), s.getSupplierCode()}
					, new String[]{
							o.getWarehouseCode(), s.getWarehouseCode()}
					, new String[]{
							o.getSiteCode(), s.getSiteCode()}
					, new String[]{
							o.getContainerCode(), s.getContainerCode()}
			)) {
				require(text(f[0]) == null || Objects.equals(f[0], f[1]), "片段与原指定条件不一致");
			}
			pieces.add(new Piece(s.getId().toString(), amount(n), s.getWarehouseCode(), text(s.getSiteCode()),
					text(s.getContainerCode()), s.getMatUnit(), "1", null,
					s.getQualityPhysicalOperationId() == null ? null : s.getQualityPhysicalOperationId()
							.toString(), identity));
		}
		if (lock && o.getSpecialReturnBindingJson() != null) {
			special.validateExecution(o, rows);
		}
		Source result = new Source(1, key(o), List.copyOf(pieces));
		parent(result, o);
		return result;
	}

	/**
	 * 无owner旧普通仅允许可证单轮单片段，复杂历史失败关闭，不补历史来源。
	 */
	private void legacy(WmsQualityStatusOperationEntity o, WmsLiveStockEntity s, int size) {
		require(size == 1 && Boolean.TRUE.equals(o.getSplitRecorded()) && o.getSplitRecordedAt() != null &&
						"[]".equals(o.getSplitLineageJson()) && text(o.getSplitRemainderStockIds()) == null,
				"历史无owner仅支持完整单轮整件证据");
		var history = operations.selectList(new LambdaQueryWrapper<WmsQualityStatusOperationEntity>().eq(
						WmsQualityStatusOperationEntity::getTenantId, o.getTenantId())
				.eq(WmsQualityStatusOperationEntity::getFactoryCode, o.getFactoryCode())
				.eq(WmsQualityStatusOperationEntity::getHoldNo, o.getHoldNo())
				.eq(WmsQualityStatusOperationEntity::getSourceType, o.getSourceType())
				.eq(WmsQualityStatusOperationEntity::getSourceId, o.getSourceId())
				.ne(WmsQualityStatusOperationEntity::getStatus, "PENDING").last("LIMIT 10001"));
		require(history.size() <= 10000, "旧处置完整历史超上限");
		int roots = 0;
		for (var h : history) {
			if (h.getId().equals(o.getId())) {
				continue;
			}
			boolean touched = Arrays.asList(Objects.toString(h.getAffectedStockIds(), "").split(","))
					.contains(s.getId().toString()) ||
					touches(h.getSplitLineageJson(), s.getId().toString());
			if (!touched) {
				continue;
			}
			require("HOLD".equals(h.getAction()) && "SUCCESS".equals(h.getStatus()) &&
							h.getCreateTime() != null && o.getCreateTime() != null &&
							!h.getCreateTime().isAfter(o.getCreateTime()) &&
							h.getRequestedQuantity().compareTo(o.getRequestedQuantity()) == 0 &&
							h.getAffectedQuantity().compareTo(o.getRequestedQuantity()) == 0 &&
							Boolean.TRUE.equals(h.getSplitRecorded()) && "[]".equals(h.getSplitLineageJson()) &&
							ids(h.getAffectedStockIds()).equals(List.of(s.getId())),
					"历史片段存在其他轮次或缺少原隔离整件证明");
			roots++;
		}
		require(roots == 1, "历史原隔离不能证明单轮归属");
	}

	/**
	 * 永久拆分映射按准确ID字段判断，禁止JSON/CSV子串冒充归属证据。
	 */
	private boolean touches(String lineage, String stockId) {
		require(lineage != null, "历史拆分映射缺失");
		List<?> links = json.decode(lineage, List.class);
		boolean touched = false;
		for (Object item : links) {
			require(item instanceof Map<?, ?>, "历史拆分映射格式错误");
			Map<?, ?> link = (Map<?, ?>) item;
			require(link.keySet().equals(Set.of("parentStockId", "remainderStockId", "remainderQuantity")),
					"历史拆分映射键集合不完整");
			require(link.get("parentStockId") instanceof String &&
					link.get("remainderStockId") instanceof String &&
					link.get("remainderQuantity") instanceof String, "历史拆分映射必须保留文本");
			String parent = (String) link.get("parentStockId");
			String remainder = (String) link.get("remainderStockId");
			id(parent);
			id(remainder);
			quantity((String) link.get("remainderQuantity"));
			touched = touched || parent.equals(stockId) || remainder.equals(stockId);
		}
		return touched;
	}
}
