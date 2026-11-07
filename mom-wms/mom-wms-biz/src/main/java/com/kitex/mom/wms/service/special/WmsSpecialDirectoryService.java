package com.kitex.mom.wms.service.special;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mdm.feign.RemoteWarehouseLocationService;
import com.kitex.mom.wms.dto.WmsSpecialMovementDTO.*;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import com.kitex.mom.wms.service.WmsMaterialReservationService;
import com.kitex.mom.wms.service.WmsSalesStockReservationService;
import com.kitex.mom.wms.service.special.WmsSpecialAccess.Scope;
import com.kitex.mom.wms.util.WmsSerialStockGuard;
import com.kitex.mom.wms.util.WmsStockAvailability;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.kitex.mom.wms.service.special.WmsSpecialRules.*;

/**
 * 特殊收发的真实物料、库位与可用库存选择。
 * 候选保留原组织、基本单位与精确来源，不生成库存，也不替代记账时的锁内复核。
 */
@Service
@RequiredArgsConstructor
public class WmsSpecialDirectoryService {
	private static final int STOCK_PAGE_SIZE = 100;
	private static final int STOCK_SCAN_BATCHES = 5;
	private static final Pattern READABLE_QUANTITY = Pattern.compile(
			"(?:0|[1-9][0-9]{0,14})(?:\\.[0-9]{1,6})?");
	private final WmsSpecialAccess access;
	private final WmsSpecialRepository repository;
	private final WmsSpecialBillProof proof;
	private final WmsLiveStockMapper stocks;
	private final WmsMaterialReservationService production;
	private final WmsSalesStockReservationService sales;
	private final RemoteMaterialService materials;
	private final RemoteWarehouseLocationService locations;

	/**
	 * 读取当前工厂启用物料，统一使用主数据基本单位。
	 *
	 * @param warehouse 已授权实际仓库编码
	 * @param keyword   物料编码查询条件，可为空
	 * @param page      从1开始的页码，每页20条
	 * @return 经组织和启用状态核对的物料选项及总数
	 */
	public MaterialPage materials(String warehouse, String keyword, int page) {
		var scope = access.scope(warehouse, false);
		access.requireAction(scope, "add");
		field(text(keyword), 100, false, "物料查询条件");
		require(page > 0 && page <= 10000, "分页参数无效");
		var filter = new MdmMaterialEntity();
		filter.setTenantId(scope.tenant());
		filter.setFactoryCode(scope.factory());
		filter.setStatus(true);
		filter.setMaterialCode(text(keyword));
		var response = materials.getMdmMaterialPage(page, 20, filter);
		var data = response == null ? null : response.getData();
		require(response != null && response.isOk() && data != null && data.getRecords() != null &&
				data.getRecords().size() <= 20, "真实物料目录读取失败");
		var result = new ArrayList<MaterialOption>();
		for (var row : data.getRecords()) {
			require(row != null && Objects.equals(row.getTenantId(), scope.tenant()) &&
							Objects.equals(row.getFactoryCode(), scope.factory()) &&
							Boolean.TRUE.equals(row.getStatus()),
					"物料目录组织或状态不一致");
			field(row.getMaterialCode(), 100, true, "物料编码");
			field(row.getBasicUnit(), 100, true, "物料基本单位");
			result.add(
					new MaterialOption(row.getMaterialCode(), text(row.getMaterialName()), row.getBasicUnit()));
		}
		return new MaterialPage(result, data.getTotal());
	}

	/**
	 * 返回原仓当前启用的实际库位；跨组织或目录读取失败时拒绝冒充空结果。
	 *
	 * @param warehouse 已授权实际仓库编码
	 * @return 本仓启用库位编码与名称
	 */
	public List<LocationOption> sites(String warehouse) {
		var scope = access.scope(warehouse, false);
		var response = locations.getLayoutLocations(scope.warehouse());
		var rows = response == null ? null : response.getData();
		require(response != null && response.isOk() && rows != null && rows.size() <= 10000,
				"实际库位读取失败");
		var result = new ArrayList<LocationOption>();
		for (var row : rows) {
			require(row != null && Objects.equals(row.getTenantId(), scope.tenant()) &&
					Objects.equals(row.getFactoryCode(), scope.factory()) &&
					Objects.equals(row.getWarehouseCode(), scope.warehouse()), "库位组织或仓库不一致");
			if (Boolean.TRUE.equals(row.getStatus())) {
				result.add(new LocationOption(row.getLocationCode(), row.getLocationName()));
			}
		}
		return List.copyOf(result);
	}

	/**
	 * 分页查询可供实际出库或原收货退回的库存，扣除生产及销售活动预留。
	 *
	 * @param warehouse    已授权实际仓库编码
	 * @param orderId      同组织原申请身份，按文本传输
	 * @param lineId       原申请基本单位物料行身份
	 * @param sourceFlowId 原收货退回时必填的实际流水身份，普通出库为空
	 * @param barcode      条码、批次或容器精确查询条件，可为空
	 * @param cursor       上次实际扫描位置，首次查询为空
	 * @return 最多100条可用候选及可继续扫描的位置；空页仍可能带后续位置
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public StockPage stocks(String warehouse, String orderId, String lineId, String sourceFlowId,
	                        String barcode,
	                        String cursor) {
		var scope = access.scope(warehouse, false);
		access.requireAction(scope, sourceFlowId == null ? "execute" : "return");
		repository.lockWarehouse(scope);
		var order = repository.order(scope, orderId);
		require(order != null, "原申请未找到");
		var line = allLines(order.plan()).stream().filter(l -> l.lineId().equals(lineId)).findFirst()
				.orElseThrow(() -> new com.kitex.common.core.exception.CheckedException("物料不属于原申请"));
		field(text(barcode), 200, false, "实物码查询条件");
		Map<String, String> original = null;
		Long sourceBillId = null;
		Long sourceDetailId = null;
		if (sourceFlowId != null) {
			id(sourceFlowId);
			var selected = repository.operations(scope, orderId).stream()
					.filter(r -> "REGISTERED".equals(r.status()))
					.flatMap(r -> r.flows().stream())
					.filter(f -> f.id().equals(sourceFlowId) && f.lineId().equals(lineId) &&
							f.sourceFlowId() == null &&
							f.direction() == Direction.IN).findFirst().orElseThrow(
							() -> new com.kitex.common.core.exception.CheckedException(
									"退回对方须选择原实际入库流水"));
			var source = proof.origin(scope, selected);
			original = selected.identity();
			require(source.getSourceInBillId() != null && source.getSourceInBillDetailId() != null,
					"原入库形成依据缺失，不能选择其它库存替代");
			sourceBillId = source.getSourceInBillId();
			sourceDetailId = source.getSourceInBillDetailId();
		}
		if (cursor != null) {
			id(cursor);
		}
		return availableStocks(scope, line, text(barcode), sourceBillId, sourceDetailId, original, cursor);
	}

	/**
	 * 按真实库存身份递减续查，最多扫描五批，避免不可用记录挤占整个可选页。
	 * 达到扫描预算时返回最后已处理位置，后续查询不跳过尚未处理的库存。
	 */
	private StockPage availableStocks(Scope scope, Line line, String barcode, Long sourceBillId,
	                                  Long sourceDetailId, Map<String, String> original, String cursor) {
		var result = new ArrayList<StockCandidate>();
		String position = cursor;
		for (int batch = 0; batch < STOCK_SCAN_BATCHES; batch++) {
			var wrapper = stockQuery(scope, line, barcode, sourceBillId, sourceDetailId, position);
			var rows = stocks.selectList(wrapper.orderByDesc(WmsLiveStockEntity::getId)
					.last("LIMIT " + (STOCK_PAGE_SIZE + 1)));
			require(rows != null && rows.size() <= STOCK_PAGE_SIZE + 1, "实际库存读取失败或超出单批范围");
			var visible = rows.stream().limit(STOCK_PAGE_SIZE).toList();
			Long previousId = position == null ? null : id(position);
			for (var stock : visible) {
				require(stock != null && stock.getId() != null && stock.getId() > 0 &&
						(previousId == null || stock.getId() < previousId) &&
						Objects.equals(stock.getTenantId(), scope.tenant()) &&
						Objects.equals(stock.getFactoryCode(), scope.factory()) &&
						Objects.equals(stock.getWarehouseCode(), scope.warehouse()) &&
						Objects.equals(stock.getMatCode(), line.materialCode()) &&
						Objects.equals(stock.getMatUnit(), line.unit()), "实际库存查询范围或顺序不一致");
				previousId = stock.getId();
			}
			var ids = visible.stream().map(WmsLiveStockEntity::getId).collect(Collectors.toSet());
			var productionReserved = ids.isEmpty() ? Map.<Long, BigDecimal>of() : production.activeReservedNums(
					ids);
			var salesReserved = ids.isEmpty() ? Map.<Long, BigDecimal>of() : sales.activeReservedNums(ids);
			require(productionReserved != null && salesReserved != null, "活动预留读取失败");
			for (int index = 0; index < visible.size(); index++) {
				var stock = visible.get(index);
				position = stock.getId().toString();
				var candidate = candidate(stock, original,
						productionReserved.getOrDefault(stock.getId(), BigDecimal.ZERO),
						salesReserved.getOrDefault(stock.getId(), BigDecimal.ZERO));
				if (candidate != null) {
					result.add(candidate);
				}
				if (result.size() == STOCK_PAGE_SIZE) {
					return new StockPage(List.copyOf(result), index + 1 < rows.size() ? position : null);
				}
			}
			if (rows.size() <= STOCK_PAGE_SIZE) {
				return new StockPage(List.copyOf(result), null);
			}
		}
		return new StockPage(List.copyOf(result), position);
	}

	/**
	 * 每批重新构造同一查询范围，来源入库身份在原件退回时保持不变。
	 */
	private LambdaQueryWrapper<WmsLiveStockEntity> stockQuery(Scope scope, Line line, String barcode,
	                                                          Long sourceBillId, Long sourceDetailId,
	                                                          String cursor) {
		var wrapper = new LambdaQueryWrapper<WmsLiveStockEntity>()
				.eq(WmsLiveStockEntity::getTenantId, scope.tenant())
				.eq(WmsLiveStockEntity::getFactoryCode, scope.factory())
				.eq(WmsLiveStockEntity::getWarehouseCode, scope.warehouse())
				.eq(WmsLiveStockEntity::getStatus, 0)
				.eq(WmsLiveStockEntity::getMatCode, line.materialCode())
				.eq(WmsLiveStockEntity::getMatUnit, line.unit())
				.isNotNull(WmsLiveStockEntity::getInTime)
				.and(!barcode.isEmpty(), query -> query.eq(WmsLiveStockEntity::getMatBarcode, barcode)
						.or().eq(WmsLiveStockEntity::getMatBatch, barcode)
						.or().eq(WmsLiveStockEntity::getContainerCode, barcode));
		if (sourceBillId != null) {
			wrapper.eq(WmsLiveStockEntity::getSourceInBillId, sourceBillId)
					.eq(WmsLiveStockEntity::getSourceInBillDetailId, sourceDetailId);
		}
		if (cursor != null) {
			wrapper.lt(WmsLiveStockEntity::getId, id(cursor));
		}
		return wrapper;
	}

	/**
	 * 复用库存可用性与单件规则，并要求数量可由既有接口精确表达。
	 * 不修补首次来源、不截断历史小数；异常预留拒绝整次查询，不能冒充可用量。
	 */
	private StockCandidate candidate(WmsLiveStockEntity stock, Map<String, String> original,
	                                 BigDecimal productionReserved, BigDecimal salesReserved) {
		require(productionReserved != null && salesReserved != null && productionReserved.signum() >= 0 &&
				salesReserved.signum() >= 0, "活动预留数量异常，请先核对预留台账");
		if (!WmsStockAvailability.isUsable(stock) || stock.getInTime() == null || stock.getMatNum() == null ||
				!Double.isFinite(stock.getMatNum()) ||
				original != null && !WmsSpecialStockProof.sameCurrentStock(original, stock)) {
			return null;
		}
		var onHand = BigDecimal.valueOf(stock.getMatNum());
		var available = onHand.subtract(productionReserved).subtract(salesReserved);
		if (available.signum() <= 0 ||
				Boolean.TRUE.equals(stock.getSerialManaged()) && available.compareTo(onHand) != 0) {
			return null;
		}
		if (!readableQuantity(onHand) || !readableQuantity(available)) {
			return null;
		}
		try {
			WmsSerialStockGuard.requireWhole(stock, onHand);
		} catch (CheckedException invalidStock) {
			return null;
		}
		return new StockCandidate(stock.getId().toString(), stock.getMatCode(), stock.getMatName(),
				stock.getMatUnit(), number(onHand), number(available), stock.getSiteCode(),
				text(stock.getContainerCode()), text(stock.getMatBatch()), text(stock.getMatBarcode()));
	}

	/**
	 * 可用量是精确预留差额，只要求文本协议可读，不把差额强转Double或截断小数。
	 */
	private boolean readableQuantity(BigDecimal value) {
		return value.signum() > 0 && READABLE_QUANTITY.matcher(number(value)).matches();
	}
}
