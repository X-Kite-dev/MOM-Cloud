package com.kitex.mom.wms.service.overreturn;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.mom.wms.dto.WmsOverReturnDTO.*;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.*;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.mdm.feign.RemoteWarehouseLocationService;
import com.kitex.mom.wms.enums.InBillTypeEnum;
import com.kitex.mom.wms.enums.InOutEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static com.kitex.mom.wms.service.overreturn.WmsOverReturnRules.*;
import static com.kitex.mom.wms.service.overreturn.WmsOverReturnStore.*;

/**
 * 普通调拨溢收的原件分批实收与永久原号恢复；本服务不依赖原差异编排，避免循环。
 */
@Service
@RequiredArgsConstructor
public class WmsOverReturnService {
	private final WmsOverReturnAccess access;
	private final WmsOverReturnStore store;
	private final WmsOverReturnCodec codec;
	private final WmsTransferVarianceCaseMapper cases;
	private final WmsAllocationBillMapper bills;
	private final WmsTransferVarianceLineMapper lines;
	private final WmsInoutFlowMapper flows;
	private final WmsLiveStockService stocks;
	private final WmsContainerBarcodeMapper containers;
	private final WmsContainerUseHistoryMapper containerHistory;
	private final WmsLiveStockMapper liveStock;
	private final WmsWarehouseBusinessGuard business;
	private final RemoteWarehouseService warehouses;
	private final RemoteWarehouseLocationService locations;

	/**
	 * 纯读分配下一批次号码；无预占，不可替换未知原号。
	 */
	public Context context(String warehouse, boolean mobile) {
		var s = access.scope();
		boolean view = access.allowed(warehouse, "view", false), end = access.allowed(warehouse, "cancel", false);
		require(view || end, "未获得原仓查看资格");
		if (end) {
			try {
				access.writeFactory(s);
			} catch (com.kitex.common.core.exception.CheckedException e) {
				end = false;
			}
		}
		boolean enabled = false, receive = false;
		try {
			enabled = business.mobileEnabled(warehouse);
			receive = end && business.check(warehouse, "allocation", false) && (!mobile || enabled);
		} catch (com.kitex.common.core.exception.CheckedException e) {
			receive = false;
		}
		return new Context(s.tenant().toString(), s.actor().toString(), s.actor().toString(), s.factory(), warehouse,
				view || end, receive, end, enabled, "OR1:" + s.actor() + ":" + UUID.randomUUID());
	}

	/**
	 * 所有候选授权与过滤均在SQL分页前完成。
	 */
	public Page pending(String warehouse, String scope, String billNo, String barcode, long current, long size) {
		var s = access.scope();
		access.source(warehouse, false);
		require(current >= 1 && current <= Integer.MAX_VALUE && size >= 1 && size <= 100 &&
				Set.of("PENDING", "HISTORY").contains(scope), "分页或查询范围无效");
		var rows = store.pending(s.tenant(), s.factory(), warehouse, scope, billNo, barcode, size, (current - 1) * size,
				false);
		var records = rows.stream().map(r -> new Pending(str(r, "id"), str(r, "case_no"), str(r, "allocation_bill_id"),
				str(r, "allocation_bill_no"), str(r, "source_warehouse_code"), str(r, "target_warehouse_code"),
				str(r, "status"), time(r.get("dispatch_time")))).toList();
		long total = number(
				store.pending(s.tenant(), s.factory(), warehouse, scope, billNo, barcode, size, 0, true).get(0),
				"total");
		return new Page(records, current, size, total);
	}

	/**
	 * 按原仓岗位或目标仓差异查看资格读取已派出原件，不重核今日库存与开关。
	 */
	public Detail detail(String caseId) {
		var s = access.scope();
		var c = caseRow(id(caseId), s, false);
		boolean source = access.allowed(c.getSourceWarehouseCode(), "view", false) ||
				access.allowed(c.getSourceWarehouseCode(), "cancel", false);
		if (!source) {
			require(com.kitex.common.security.util.SecurityUtils.getAuthentication() != null, "缺少认证");
			require(newTargetRead(c.getTargetWarehouseCode()), "无原目标仓差异查看资格");
		}
		var manifest = store.manifests(s.tenant(), s.factory(), c.getId());
		require(!manifest.isEmpty(), "此案件没有新返还发出原件，请从原差异履历查看历史");
		List<Line> items = new ArrayList<>();
		for (var m : manifest) {
			var sourceFlow = codec.source(str(m, "source_snapshot"));
			var q = new BigDecimal(str(m, "dispatched_quantity"));
			var received = store.received(s.tenant(), sourceFlow.getId());
			require(received.signum() >= 0 && received.compareTo(q) <= 0, "返还流水累计与原件不一致");
			items.add(new Line(str(m, "variance_line_id"), sourceFlow.getId().toString(), sourceFlow.getMatCode(),
					sourceFlow.getMatName(), sourceFlow.getMatBatch(), sourceFlow.getMatUnit(),
					sourceFlow.getMatBarcode(), Boolean.TRUE.equals(sourceFlow.getSerialManaged()), decimal(q),
					decimal(received), decimal(q.subtract(received))));
		}
		var receipts = store.receipts(s.tenant(), s.factory(), c.getId()).stream().map(this::receipt).toList();
		var first = manifest.get(0);
		return new Detail(caseId, c.getCaseNo(), c.getAllocationBillId().toString(), c.getAllocationBillNo(),
				c.getSourceWarehouseCode(), c.getTargetWarehouseCode(), c.getStatus(),
				str(first, "dispatch_request_id"), str(first, "dispatch_evidence"), time(first.get("dispatch_time")),
				items, receipts);
	}

	/**
	 * 实收、查原号与安全结束共用tenant+request仲裁及case/bill锁；已登记终态优先于今日状态。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Receipt execute(Envelope envelope, String action, boolean mobile) {
		var s = access.scope();
		require(envelope != null, "缺少原请求");
		var command = codec.command(envelope.commandText());
		require(envelope.requestId() != null && envelope.requestId()
						.matches("OR1:" + s.actor() + ":[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"),
				"原号必须为本人的服务端号码");
		access.source(command.warehouseCode(), false);
		store.lock(s.tenant(), envelope.requestId());
		var previous = store.request(s.tenant(), envelope.requestId());
		if (previous != null) {
			require(Objects.equals(str(previous, "factory_code"), s.factory()) &&
							number(previous, "actor_id") == s.actor() &&
							Objects.equals(str(previous, "command_text"), envelope.commandText()) &&
							Objects.equals(str(previous, "case_id"), command.caseId()) &&
							Objects.equals(str(previous, "source_warehouse_code"), command.warehouseCode()),
					"原号不属于本人原案、组织或全文");
			return receipt(previous);
		}
		if (!"lookup".equals(action)) {
			access.source(command.warehouseCode(), true);
			access.writeFactory(s);
		}
		var c = caseRow(id(command.caseId()), s, true);
		require(command.warehouseCode().equals(c.getSourceWarehouseCode()), "命令不是原调出仓");
		var b = bills.selectOne(new LambdaQueryWrapper<WmsAllocationBillEntity>().eq(WmsAllocationBillEntity::getId,
						c.getAllocationBillId()).eq(WmsAllocationBillEntity::getTenantId, s.tenant())
				.eq(WmsAllocationBillEntity::getFactoryCode, s.factory()).last("FOR UPDATE"));
		require(b != null && Objects.equals(b.getOldWarehouseCode(), c.getSourceWarehouseCode()) &&
				Objects.equals(b.getNewWarehouseCode(), c.getTargetWarehouseCode()), "原调拨单与差异仓库不一致");
		var manifest = store.manifests(s.tenant(), s.factory(), c.getId());
		require(!manifest.isEmpty(), "原案尚无真实发出原件");
		Map<Long, Map<String, Object>> byFlow = new TreeMap<>();
		for (var m : manifest) {
			byFlow.put(number(m, "adjustment_flow_id"), m);
		}
		Map<Long, ReceiveLine> requested = new TreeMap<>();
		for (var line : command.lines()) {
			long flow = id(line.adjustmentFlowId());
			var m = byFlow.get(flow);
			require(m != null, "片段不属于原案发出原件");
			var original = codec.source(str(m, "source_snapshot"));
			require(Objects.equals(original.getMatUnit(), line.unit()), "实收单位与原件不符");
			require(line.barcode() == null || Objects.equals(line.barcode(), original.getMatBarcode()),
					"实收条码与原件不符");
			if (Boolean.TRUE.equals(original.getSerialManaged())) {
				require(original.getMatBarcode() != null && original.getMatBarcode().equals(line.barcode()) &&
						quantity(line.quantity()).compareTo(BigDecimal.ONE) == 0, "序列件必须完整扫码接收一件");
			}
			requested.put(flow, line);
		}
		if ("lookup".equals(action)) {
			return new Receipt(envelope.requestId(), "NOT_FOUND", command.caseId(), b.getId().toString(),
					command.warehouseCode(), s.actor().toString(), null, null, null, List.of(), null);
		}
		if (!"lookup".equals(action)) {
			store.ready();
		}
		// 结案与永久回执必须使用同一真实展示名；姓名为空或仅空白时沿原规则回退登录账号。
		String operatorName = s.name() == null || s.name().isBlank() ? s.operator() : s.name();
		LocalDateTime now = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
		List<Effect> effects = new ArrayList<>();
		String after = null;
		if ("receive".equals(action)) {
			require(Set.of("RETURN_IN_TRANSIT", "RETURN_PARTIAL").contains(c.getStatus()) &&
					"DISCREPANCY_PENDING".equals(b.getAllocationState()), "原案不在待实收状态");
			var lockedLines = lines.selectList(new LambdaQueryWrapper<WmsTransferVarianceLineEntity>().eq(
							WmsTransferVarianceLineEntity::getTenantId, s.tenant())
					.eq(WmsTransferVarianceLineEntity::getCaseId, c.getId())
					.orderByAsc(WmsTransferVarianceLineEntity::getId).last("FOR UPDATE"));
			require(!lockedLines.isEmpty(), "原差异行不存在");
			business.check(command.warehouseCode(), "allocation", false);
			if (mobile) {
				business.checkMobile(command.warehouseCode());
			}
			business.checkPlacements(command.warehouseCode(), requested.values().stream()
					.map(l -> new WmsWarehousePlacementGuard.Placement(l.siteCode(), l.containerCode())).toList());
			// 先按容器编码固定顺序加锁，核实际占用落点，防止同批不同库位或异物料混装。
			var containerBindings = new TreeMap<String, ReceiveLine>();
			for (var l : requested.values()) {
				if (l.containerCode() != null) {
					var prior = containerBindings.putIfAbsent(l.containerCode(), l);
					require(prior == null || prior.siteCode().equals(l.siteCode()), "同一容器本批不能放置到多个库位");
				}
			}
			for (var entry : containerBindings.entrySet()) {
				var container = containers.selectOne(
						new LambdaQueryWrapper<WmsContainerBarcodeEntity>().eq(WmsContainerBarcodeEntity::getTenantId,
										s.tenant()).eq(WmsContainerBarcodeEntity::getFactoryCode, s.factory())
								.eq(WmsContainerBarcodeEntity::getCode, entry.getKey())
								.eq(WmsContainerBarcodeEntity::getWarehouseCode, command.warehouseCode())
								.last("FOR UPDATE"));
				require(container != null && Set.of(0, 1).contains(container.getIsUsed()), "容器状态或原仓范围无效");
				var occupants = liveStock.selectList(
						new LambdaQueryWrapper<WmsLiveStockEntity>().eq(WmsLiveStockEntity::getTenantId, s.tenant())
								.eq(WmsLiveStockEntity::getFactoryCode, s.factory())
								.eq(WmsLiveStockEntity::getContainerCode, entry.getKey())
								.orderByAsc(WmsLiveStockEntity::getId).last("FOR UPDATE"));
				for (var stock : occupants) {
					require(command.warehouseCode().equals(stock.getWarehouseCode()) &&
							entry.getValue().siteCode().equals(stock.getSiteCode()), "容器实际占用位置与本次落点不符");
				}
				var active = containerHistory.selectList(new LambdaQueryWrapper<WmsContainerUseHistoryEntity>().eq(
								WmsContainerUseHistoryEntity::getTenantId, s.tenant())
						.eq(WmsContainerUseHistoryEntity::getFactoryCode, s.factory())
						.eq(WmsContainerUseHistoryEntity::getCode, entry.getKey())
						.isNull(WmsContainerUseHistoryEntity::getEndTime));
				require(container.getIsUsed() == 0 || !active.isEmpty() || !occupants.isEmpty(),
						"占用容器缺少可核验实物关系");
				for (var history : active) {
					require(entry.getValue().siteCode().equals(history.getSiteCode()),
							"容器当前使用位置与实收库位不符");
				}
			}
			var warehouseResponse = warehouses.getByCode(command.warehouseCode());
			require(warehouseResponse != null && warehouseResponse.isOk() && warehouseResponse.getData() != null,
					"实际仓库读取失败");
			var warehouse = warehouseResponse.getData();
			// 数据库见证只作用于本原号的实际新增库存，不改变普通入库写入路径。
			store.receiveIntent(envelope.requestId());
			for (var entry : requested.entrySet()) {
				var m = byFlow.get(entry.getKey());
				var original = codec.source(str(m, "source_snapshot"));
				var line = entry.getValue();
				var q = quantity(line.quantity());
				var prior = store.received(s.tenant(), entry.getKey());
				var dispatched = new BigDecimal(str(m, "dispatched_quantity"));
				require(prior.add(q).compareTo(dispatched) <= 0, "本批数量超过原件在途余量");
				var siteResponse = locations.getByCode(line.siteCode());
				require(siteResponse != null && siteResponse.isOk() && siteResponse.getData() != null,
						"实际库位读取失败");
				var site = siteResponse.getData();
				WmsInoutFlowEntity inbound = new WmsInoutFlowEntity();
				BeanUtils.copyProperties(original, inbound);
				inbound.setMatNum(q.doubleValue());
				// 库龄已经由原件完整性验证，禁止transferIn的历史默认时间路径创造新库龄。
				require(inbound.getStockInTime() != null, "原件库龄缺失");
				Long stock = stocks.transferInWithEvidence(inbound, command.warehouseCode(),
						warehouse.getWarehouseName(), line.siteCode(), site.getLocationName(), line.containerCode());
				require(stock != null && stock > 0, "未取得真实库存身份");
				inbound.setId(null);
				inbound.setCreateBy(null);
				inbound.setUpdateBy(null);
				inbound.setCreateTime(null);
				inbound.setUpdateTime(null);
				inbound.setDelFlag("0");
				inbound.setTransferReceiptId(null);
				inbound.setBillDetailId(null);
				inbound.setSourceFlowId(entry.getKey());
				inbound.setSourceStockId(stock);
				inbound.setBillId(b.getId());
				inbound.setBillNo(b.getBillNo());
				inbound.setWarehouseCode(command.warehouseCode());
				inbound.setWarehouseName(warehouse.getWarehouseName());
				inbound.setSiteCode(line.siteCode());
				inbound.setSiteName(site.getLocationName());
				inbound.setContainerCode(line.containerCode());
				inbound.setTransferAction("OVER_RETURN");
				inbound.setOperationRequestId(envelope.requestId());
				inbound.setVarianceCaseId(c.getId());
				inbound.setVarianceLineId(number(m, "variance_line_id"));
				inbound.setOperateType(InOutEnum.IN.getCode());
				inbound.setBillType(InBillTypeEnum.ALLOCATION.getCode());
				require(flows.insert(inbound) == 1 && inbound.getId() != null, "返还实收入库流水写入失败");
				effects.add(new Effect(entry.getKey().toString(), str(m, "variance_line_id"), original.getMatCode(),
						original.getMatBatch(), original.getMatUnit(), line.barcode(), line.quantity(), line.siteCode(),
						line.containerCode(), stock.toString(), inbound.getId().toString(), decimal(prior.add(q)),
						decimal(dispatched.subtract(prior).subtract(q))));
				if (prior.add(q).compareTo(dispatched) == 0) {
					stocks.releaseTransferSourceContainer(original);
				}
			}
			boolean complete = true;
			Map<Long, Boolean> lineComplete = new TreeMap<>();
			for (var m : manifest) {
				boolean full = store.received(s.tenant(), number(m, "adjustment_flow_id"))
						.compareTo(new BigDecimal(str(m, "dispatched_quantity"))) == 0;
				complete &= full;
				lineComplete.merge(number(m, "variance_line_id"), full, (a, z) -> a && z);
			}
			for (var item : lineComplete.entrySet()) {
				var l = lines.selectOne(
						new LambdaQueryWrapper<WmsTransferVarianceLineEntity>().eq(WmsTransferVarianceLineEntity::getId,
										item.getKey()).eq(WmsTransferVarianceLineEntity::getTenantId, s.tenant())
								.eq(WmsTransferVarianceLineEntity::getCaseId, c.getId()).last("FOR UPDATE"));
				require(l != null, "原差异明细不存在");
				l.setDispositionStatus(item.getValue() ? "RESOLVED" : "PENDING");
				require(lines.updateById(l) == 1, "返还行状态更新失败");
			}
			after = complete ? "RESOLVED" : "RETURN_PARTIAL";
			c.setStatus(after);
			if (complete) {
				c.setResolvedBy(s.operator()).setResolvedByName(operatorName).setResolvedTime(now);
				b.setAllocationState("DISCREPANCY_RESOLVED");
				require(bills.updateById(b) == 1, "原单结案更新失败");
			}
			require(cases.updateById(c) == 1, "返还状态更新失败");
		} else {
			require("end".equals(action), "未知返还操作");
		}
		Receipt result = new Receipt(envelope.requestId(), "receive".equals(action) ? "APPLIED" : "CANCELLED",
				command.caseId(), b.getId().toString(), command.warehouseCode(), s.actor().toString(),
				operatorName, eventTime(now), after, List.copyOf(effects),
				"receive".equals(action) ? command.evidence() : null);
		store.save(IdUtil.getSnowflakeNextId(), s, envelope, command, result, codec.encode(result.effects()), now);
		return result;
	}

	private boolean newTargetRead(String warehouse) {
		return com.kitex.common.security.util.SecurityUtils.getAuthentication().getAuthorities().stream()
				.anyMatch(a -> "wms_transferVariance_view".equals(a.getAuthority())) &&
				(access.allowed(warehouse, "view", true) || access.allowed(warehouse, "rec", true));
	}

	private WmsTransferVarianceCaseEntity caseRow(long id, WmsOverReturnAccess.Scope s, boolean lock) {
		var q = new LambdaQueryWrapper<WmsTransferVarianceCaseEntity>().eq(WmsTransferVarianceCaseEntity::getId, id)
				.eq(WmsTransferVarianceCaseEntity::getTenantId, s.tenant())
				.eq(WmsTransferVarianceCaseEntity::getFactoryCode, s.factory());
		if (lock) {
			q.last("FOR UPDATE");
		}
		var c = cases.selectOne(q);
		require(c != null, "当前组织不存在此差异单");
		return c;
	}

	private Receipt receipt(Map<String, Object> row) {
		String status = str(row, "status");
		var effects = codec.effects(str(row, "effects_json"));
		require(Set.of("APPLIED", "CANCELLED").contains(status) && row.get("handled_time") != null &&
				str(row, "operator_name") != null && !str(row, "operator_name").isBlank(), "永久原号终态残缺");
		id(str(row, "actor_id"));
		id(str(row, "case_id"));
		id(str(row, "bill_id"));
		require("APPLIED".equals(status) ?
				!effects.isEmpty() && Set.of("RETURN_PARTIAL", "RESOLVED").contains(str(row, "after_status")) :
				effects.isEmpty() && row.get("after_status") == null, "永久回执状态或效果不完整");
		var command = codec.command(str(row, "command_text"));
		require(command.caseId().equals(str(row, "case_id")) &&
				command.warehouseCode().equals(str(row, "source_warehouse_code")), "永久命令与原回执范围不符");
		Map<String, ReceiveLine> requested = new HashMap<>();
		for (var l : command.lines()) {
			requested.put(l.adjustmentFlowId(), l);
		}
		require(!"APPLIED".equals(status) || effects.size() == requested.size(), "永久效果与本批片段数量不符");
		Set<String> ids = new HashSet<>(), stocks = new HashSet<>(), flows = new HashSet<>();
		for (var e : effects) {
			var l = requested.get(e.adjustmentFlowId());
			require(l != null && l.quantity().equals(e.quantity()) && l.unit().equals(e.unit()) &&
					Objects.equals(l.barcode(), e.barcode()) && Objects.equals(l.siteCode(), e.siteCode()) &&
					Objects.equals(l.containerCode(), e.containerCode()), "永久效果与原封命令不符");
			id(e.adjustmentFlowId());
			id(e.varianceLineId());
			id(e.stockId());
			id(e.inboundFlowId());
			quantity(e.quantity());
			require(ids.add(e.adjustmentFlowId()) && stocks.add(e.stockId()) && flows.add(e.inboundFlowId()) &&
					new BigDecimal(e.receivedQuantity()).signum() > 0 &&
					new BigDecimal(e.remainingQuantity()).signum() >= 0, "永久效果重复或数量无效");
		}
		return new Receipt(str(row, "request_id"), status, str(row, "case_id"), str(row, "bill_id"),
				str(row, "source_warehouse_code"), str(row, "actor_id"), str(row, "operator_name"),
				time(row.get("handled_time")), str(row, "after_status"), effects,
				"APPLIED".equals(status) ? command.evidence() : null);
	}

	private String time(Object value) {
		return value == null ? null : eventTime(
				value instanceof java.sql.Timestamp t ? t.toLocalDateTime() : (LocalDateTime) value);
	}
}
