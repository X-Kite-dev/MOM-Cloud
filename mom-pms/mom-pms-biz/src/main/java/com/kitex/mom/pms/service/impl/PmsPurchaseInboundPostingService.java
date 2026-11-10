package com.kitex.mom.pms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.pms.dto.PmsInNoticeOrderDTO;
import com.kitex.mom.pms.dto.PmsPurchaseInboundPosting;
import com.kitex.mom.pms.entity.PmsOrderDetailEntity;
import com.kitex.mom.pms.entity.PmsOrderEntity;
import com.kitex.mom.pms.entity.WmsPurchaseReceiptBatchDetailEntity;
import com.kitex.mom.pms.entity.WmsPurchaseReceiptDetailEntity;
import com.kitex.mom.pms.entity.WmsPurchaseReceiptEntity;
import com.kitex.mom.pms.enums.PmsOrderStatusEnum;
import com.kitex.mom.pms.mapper.PmsOrderDetailMapper;
import com.kitex.mom.pms.mapper.PmsOrderMapper;
import com.kitex.mom.pms.mapper.PmsPurchaseInboundPostingMapper;
import com.kitex.mom.pms.mapper.WmsPurchaseReceiptBatchDetailMapper;
import com.kitex.mom.pms.mapper.WmsPurchaseReceiptDetailMapper;
import com.kitex.mom.pms.mapper.WmsPurchaseReceiptMapper;
import com.kitex.mom.pms.service.PmsOrderService;
import com.kitex.mom.wms.api.feign.RemoteWmsPurchaseInboundPostingProofService;
import com.kitex.mom.wms.enums.PurchaseReceiptStateEnum;
import com.kitex.mom.wms.enums.YesNoEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 受理已正式过账的 WMS 采购入库事件。在同一 PMS 事务写入批次、收料行、到货及永久回执。
 * V1 限制为无 PLAN 下发的采购，避免在本地事务内再次产生远程数量增量。
 */
@Service
@RequiredArgsConstructor
public class PmsPurchaseInboundPostingService {
	private static final String COMPLETED = PurchaseReceiptStateEnum.COMPLETED.getCode();
	private static final String READY = PurchaseReceiptStateEnum.QUALITY_FINISHED.getCode();
	private static final String QUALITY_PENDING = PurchaseReceiptStateEnum.QUALITY_COMMIT.getCode();
	private static final Set<String> RECEIVABLE_ORDER = Set.of(PmsOrderStatusEnum.CHECK_SUCCESS.getCode(),
			PmsOrderStatusEnum.RECEIVING.getCode(), PmsOrderStatusEnum.PART_RECEIVE.getCode());

	private final PmsPurchaseInboundPostingMapper postings;
	private final WmsPurchaseReceiptMapper receipts;
	private final WmsPurchaseReceiptDetailMapper receiptLines;
	private final WmsPurchaseReceiptBatchDetailMapper batches;
	private final PmsOrderMapper orders;
	private final PmsOrderDetailMapper orderLines;
	private final PmsOrderService orderService;
	private final RemoteMdmFactoryAccessService factories;
	private final RemoteWmsPurchaseInboundPostingProofService wmsProofs;
	private final ObjectMapper json;

	/**
	 * 只读核验原收料的状态、目标仓及关联计划。外部下发或无原采购订单时沿用原办理方式。
	 *
	 * @param purchaseReceiptId 当前实际工厂的原收料主键
	 * @param warehouseCode WMS 原采购入库通知的目标仓库编码
	 * @return 是否可走本地闭环的 V1 采购入库
	 */
	public boolean eligible(Long purchaseReceiptId, String warehouseCode) {
		Scope scope = scope("read");
		var receipt = receipt(scope, purchaseReceiptId, false);
		requirePostingWarehouse(receipt, warehouseCode);
		if (StrUtil.isBlank(receipt.getPmsOrderNo())) {
			return false;
		}
		var order = order(receipt, false);
		boolean localPosting = readPlans(scope, receipt, orderLines(scope, order.getId(), false), false).values().stream()
				.noneMatch(plan -> StrUtil.isNotBlank(plan.getOrderNo()));
		if (localPosting) {
			requirePostingReceiptState(receipt);
			require(RECEIVABLE_ORDER.contains(order.getStatus()), "原采购订单当前状态不允许入库回写");
		}
		return localPosting;
	}

	/**
	 * 查询同一实际组织的永久回执。空表示未查到提交记录。
	 *
	 * @param wmsBillId 原 WMS 正式入库单主键
	 * @return 已保存回执或空
	 */
	public PmsPurchaseInboundPosting.Receipt lookup(Long wmsBillId) {
		Scope scope = scope("read");
		require(wmsBillId != null && wmsBillId > 0, "原WMS入库单主键无效");
		return postings.receipt(scope.tenant(), scope.factory(), wmsBillId);
	}

	/**
	 * 事务内按原号串行，核验原收料来源及全部数量后过账。
	 *
	 * @param command WMS 已过账原单和精确行的冻结快照
	 * @return 首次受理结果或同载荷的永久原回执
	 */
	@Transactional(rollbackFor = Exception.class)
	public PmsPurchaseInboundPosting.Receipt accept(PmsPurchaseInboundPosting command) {
		require(command != null, "采购入库过账事件不能为空");
		command.validate();
		Scope scope = scope("write");
		require(Objects.equals(command.factoryCode(), scope.factory()), "采购入库事件工厂与实际上下文不符");
		String fingerprint = command.fingerprint();
		String key = "PMS-PURCHASE-INBOUND:" + scope.tenant() + ":" + scope.factory() + ":" + command.wmsBillId();
		require(Objects.equals(postings.lockEvent(key), 1), "无法锁定原WMS入库单回执");
		var original = postings.receipt(scope.tenant(), scope.factory(), command.wmsBillId());
		if (original != null) {
			require(Objects.equals(original.fingerprint(), fingerprint)
							&& Objects.equals(original.purchaseReceiptId(), command.purchaseReceiptId())
							&& original.lineCount() == command.lines().size(),
					"原WMS入库单已受理，回写载荷不能改变");
			return original;
		}

		var proof = wmsProofs.proof(command.wmsBillId());
		require(proof != null && proof.isOk() && Objects.equals(fingerprint, proof.getData()),
				"WMS 未确认此采购入库单的已过账冻结事实");
		var initial = receipt(scope, command.purchaseReceiptId(), false);
		var order = order(initial, true);
		require(RECEIVABLE_ORDER.contains(order.getStatus()), "原采购订单当前状态不允许入库回写");
		// 与受控订单变更和旧回写共用订单优先的锁顺序，再锁原收料和批次。
		var originalOrderLines = orderLines(scope, order.getId(), true);
		var source = receipt(scope, initial.getId(), true);
		requireSameSource(initial, source);
		requirePostingReceiptState(source);
		requirePostingWarehouse(source, command.warehouseCode());
		var plans = readPlans(scope, source, originalOrderLines, true);
		require(plans.values().stream().noneMatch(plan -> StrUtil.isNotBlank(plan.getOrderNo())),
				"关联PLAN下发的采购计划须使用原办理方式");

		var updates = prepare(scope, source.getId(), order.getId(), originalOrderLines, command.lines());
		writeReceiptQuantities(scope, source, updates);
		writeOrderArrivals(scope, order, originalOrderLines, plans, updates);
		var result = new PmsPurchaseInboundPosting.Receipt(command.wmsBillId(), command.purchaseReceiptId(),
				fingerprint, command.lines().size());
		require(postings.saveReceipt(scope.tenant(), scope.factory(), result, freezePayload(command)) == 1,
				"采购入库永久回执保存失败");
		return result;
	}

	/**
	 * 旧逐次增量仍沿旧协议记账，先占同一订单及收料头锁，避免与 V1 反序写同源。
	 * 不更改手工无订单收料的原办理语义。
	 *
	 * @param purchaseReceiptId 原收料单主键
	 */
	public void lockLegacySource(Long purchaseReceiptId) {
		var initial = receipts.selectById(purchaseReceiptId);
		if (initial == null || StrUtil.isBlank(initial.getPmsOrderNo())) {
			return;
		}
		// 旧合同未要求供应商快照。按旧订单关联先加锁，新 V1 的强来源校验仅用于 V1。
		var matchingOrders = orders.selectList(Wrappers.<PmsOrderEntity>lambdaQuery()
				.eq(PmsOrderEntity::getTenantId, initial.getTenantId())
				.eq(PmsOrderEntity::getFactoryCode, initial.getFactoryCode())
				.eq(PmsOrderEntity::getPmsOrderNo, initial.getPmsOrderNo()).last("LIMIT 2 FOR UPDATE"));
		require(matchingOrders.size() == 1, "原采购订单不存在或在当前组织内不唯一");
		var locked = receipts.selectOne(Wrappers.<WmsPurchaseReceiptEntity>lambdaQuery()
				.eq(WmsPurchaseReceiptEntity::getId, initial.getId())
				.eq(WmsPurchaseReceiptEntity::getTenantId, initial.getTenantId())
				.eq(WmsPurchaseReceiptEntity::getFactoryCode, initial.getFactoryCode()).last("FOR UPDATE"));
		requireSameSource(initial, locked);
	}

	private PostingUpdates prepare(Scope scope, Long receiptId, Long orderId,
	                               Map<Long, PmsOrderDetailEntity> originalOrderLines,
	                               List<PmsPurchaseInboundPosting.Line> lines) {
		Map<Long, BigDecimal> byBatch = new TreeMap<>();
		Map<Long, BigDecimal> byOrder = new TreeMap<>();
		Map<Long, PmsPurchaseInboundPosting.Line> sourceLineByBatch = new TreeMap<>();
		for (var line : lines) {
			var orderLine = originalOrderLines.get(line.orderDetailId());
			require(orderLine != null && Objects.equals(orderLine.getMatCode(), line.materialCode())
							&& Objects.equals(orderLine.getMatUnit(), line.unit()),
					"入库物料或标准单位与原采购订单行不一致");
			var previous = sourceLineByBatch.putIfAbsent(line.receiptBatchId(), line);
			require(previous == null || Objects.equals(previous.orderDetailId(), line.orderDetailId())
							&& Objects.equals(previous.materialCode(), line.materialCode())
							&& Objects.equals(previous.batchNo(), line.batchNo())
							&& Objects.equals(previous.unit(), line.unit()),
					"同一原收料批次在入库单中具有不同来源");
			byBatch.merge(line.receiptBatchId(), line.quantity(), BigDecimal::add);
			byOrder.merge(line.orderDetailId(), line.quantity(), BigDecimal::add);
		}
		List<BatchChange> batchChanges = new ArrayList<>();
		for (var row : byBatch.entrySet()) {
			var line = sourceLineByBatch.get(row.getKey());
			var batch = postings.lockBatch(scope.tenant(), scope.factory(), receiptId, row.getKey());
			require(batch != null && Objects.equals(batch.getOrderDetailId(), line.orderDetailId())
							&& Objects.equals(batch.getMatCode(), line.materialCode())
							&& Objects.equals(batch.getMatUnit(), line.unit())
							&& Objects.equals(batch.getMatBatch(), line.batchNo())
							&& READY.equals(batch.getState())
							&& (String.valueOf(YesNoEnum.YES.getCode()).equals(batch.getQualifiedGen())
									|| String.valueOf(YesNoEnum.YES.getCode()).equals(batch.getUnQualifiedGen())),
					"原收料批次、原订单行、单位、质量状态或通知生成状态不一致");
			BigDecimal total = capped(batch.getInNum(), row.getValue(), batch.getQualityNum(), "原收料批次");
			String next = total.compareTo(batch.getQualityNum()) == 0 ? COMPLETED : READY;
			batchChanges.add(new BatchChange(row.getKey(), batch.getInNum(), total, next));
		}
		List<ReceiptLineChange> receiptChanges = new ArrayList<>();
		Map<Long, BigDecimal> expectedArrivals = new TreeMap<>();
		for (var row : byOrder.entrySet()) {
			var sourceOrderLine = originalOrderLines.get(row.getKey());
			var matches = postings.lockReceiptLines(scope.tenant(), scope.factory(), receiptId, row.getKey(),
					sourceOrderLine.getMatCode());
			require(matches.size() == 1, "原收料行不存在或无法唯一确认");
			var receiptLine = matches.get(0);
			require(Boolean.TRUE.equals(receiptLine.getWmsStatus())
							&& Objects.equals(receiptLine.getMatUnit(), sourceOrderLine.getMatUnit()),
					"原收料行尚未生成仓储通知或标准单位不一致");
			BigDecimal received = capped(receiptLine.getInNum(), row.getValue(), receiptLine.getActualNum(), "原收料行");
			receiptChanges.add(new ReceiptLineChange(receiptLine.getId(), receiptLine.getInNum(), received));
			var orderQuantity = postings.orderQuantity(scope.tenant(), scope.factory(), orderId, row.getKey());
			require(orderQuantity != null && orderQuantity.getOrderNum() != null
							&& orderQuantity.getCancelledNum() != null && orderQuantity.getCancelledNum().signum() >= 0,
					"原采购订单行订购或撤回数量无法核验");
			expectedArrivals.put(row.getKey(), capped(orderQuantity.getArrivalNum(), row.getValue(),
					orderQuantity.getOrderNum().subtract(orderQuantity.getCancelledNum()), "原采购订单行"));
		}
		return new PostingUpdates(batchChanges, receiptChanges, byOrder, expectedArrivals);
	}

	private void writeReceiptQuantities(Scope scope, WmsPurchaseReceiptEntity source, PostingUpdates updates) {
		for (var change : updates.batches()) {
			require(postings.updateBatch(scope.tenant(), scope.factory(), source.getId(), change.id(), change.before(),
					change.total(), change.state(), scope.actor()) == 1, "原收料批次入库累计未写入");
		}
		for (var change : updates.receiptLines()) {
			require(postings.updateReceiptLine(scope.tenant(), scope.factory(), source.getId(), change.id(),
					change.before(), change.total(), scope.actor()) == 1, "原收料行入库累计未写入");
		}
		long unfinishedBatchCount = batches.selectCount(Wrappers.<WmsPurchaseReceiptBatchDetailEntity>lambdaQuery()
				.eq(WmsPurchaseReceiptBatchDetailEntity::getTenantId, scope.tenant())
				.eq(WmsPurchaseReceiptBatchDetailEntity::getBillId, source.getId())
				.and(q -> q.ne(WmsPurchaseReceiptBatchDetailEntity::getState, COMPLETED)
						.or().isNull(WmsPurchaseReceiptBatchDetailEntity::getState)));
		long ungeneratedCount = receiptLines.selectCount(Wrappers.<WmsPurchaseReceiptDetailEntity>lambdaQuery()
				.eq(WmsPurchaseReceiptDetailEntity::getTenantId, scope.tenant())
				.eq(WmsPurchaseReceiptDetailEntity::getBillId, source.getId())
				.eq(WmsPurchaseReceiptDetailEntity::getWmsStatus, false)
				.eq(WmsPurchaseReceiptDetailEntity::getIsQuality, false));
		// 部分免检批次可先过账。收料头仍在质检提交时，其他质检事实未确认，不推进整单完成。
		if (READY.equals(source.getState()) && unfinishedBatchCount == 0 && ungeneratedCount == 0) {
			require(receipts.update(Wrappers.<WmsPurchaseReceiptEntity>lambdaUpdate()
					.eq(WmsPurchaseReceiptEntity::getId, source.getId())
					.eq(WmsPurchaseReceiptEntity::getTenantId, scope.tenant())
					.eq(WmsPurchaseReceiptEntity::getFactoryCode, scope.factory())
					.eq(WmsPurchaseReceiptEntity::getState, READY)
					.set(WmsPurchaseReceiptEntity::getState, COMPLETED)) == 1, "原采购收料单完成状态未写入");
		}
	}

	private void writeOrderArrivals(Scope scope, PmsOrderEntity order, Map<Long, PmsOrderDetailEntity> originalOrderLines,
	                                Map<String, PmsPurchaseInboundPostingMapper.Plan> plans, PostingUpdates updates) {
		Map<String, BigDecimal> expectedPlans = new TreeMap<>();
		for (var row : updates.orderDeltas().entrySet()) {
			var line = originalOrderLines.get(row.getKey());
			double increment = row.getValue().doubleValue();
			require(Double.isFinite(increment) && BigDecimal.valueOf(increment).compareTo(row.getValue()) == 0,
					"本次到货数量超出原订单回写的精度");
			PmsInNoticeOrderDTO delta = new PmsInNoticeOrderDTO();
			delta.setPmsOrderNo(order.getPmsOrderNo());
			delta.setOrderDetailId(line.getId());
			delta.setMatCode(line.getMatCode());
			delta.setNum(increment);
			require(Boolean.TRUE.equals(orderService.inNoticeSave(delta)), "原采购订单到货未确认");
			var persisted = postings.orderQuantity(scope.tenant(), scope.factory(), order.getId(), line.getId());
			require(persisted != null && persisted.getArrivalNum().compareTo(updates.expectedArrivals().get(line.getId())) == 0,
					"原采购订单行到货累计与本次实际入库不一致");
			if (StrUtil.isNotBlank(line.getPmsPlanNo())) {
				String planNo = line.getPmsPlanNo();
				var prior = plans.get(planNo);
				require(prior != null && prior.getArrivalNum() != null, "原采购计划的到货累计无法核验");
				expectedPlans.merge(planNo, row.getValue(), BigDecimal::add);
			}
		}
		for (var expected : expectedPlans.entrySet()) {
			var rows = postings.plan(scope.tenant(), scope.factory(), expected.getKey(), true);
			BigDecimal total = plans.get(expected.getKey()).getArrivalNum().add(expected.getValue());
			require(rows.size() == 1 && rows.get(0).getArrivalNum().compareTo(total) == 0,
					"原采购计划的到货累计与本次实际入库不一致");
		}
	}

	private Map<String, PmsPurchaseInboundPostingMapper.Plan> readPlans(Scope scope, WmsPurchaseReceiptEntity source,
	                          Map<Long, PmsOrderDetailEntity> originalOrderLines, boolean lock) {
		var rows = receiptLines.selectList(Wrappers.<WmsPurchaseReceiptDetailEntity>lambdaQuery()
				.eq(WmsPurchaseReceiptDetailEntity::getTenantId, scope.tenant())
				.eq(WmsPurchaseReceiptDetailEntity::getBillId, source.getId()));
		require(!rows.isEmpty(), "原采购收料单缺少明细");
		var planNumbers = rows.stream().map(row -> {
			var orderLine = originalOrderLines.get(row.getOrderDetailId());
			require(orderLine != null && Objects.equals(orderLine.getMatCode(), row.getMatCode()),
					"原收料行与采购订单行来源不符");
			return orderLine.getPmsPlanNo();
		}).filter(StrUtil::isNotBlank).distinct().sorted().toList();
		Map<String, PmsPurchaseInboundPostingMapper.Plan> plans = new TreeMap<>();
		for (String number : planNumbers) {
			var matches = postings.plan(scope.tenant(), scope.factory(), number, lock);
			require(matches.size() == 1, "原采购计划不存在或在当前组织内不唯一");
			plans.put(number, matches.get(0));
		}
		return plans;
	}

	private WmsPurchaseReceiptEntity receipt(Scope scope, Long id, boolean lock) {
		require(id != null && id > 0, "原采购收料单主键无效");
		var source = receipts.selectOne(Wrappers.<WmsPurchaseReceiptEntity>lambdaQuery()
				.eq(WmsPurchaseReceiptEntity::getTenantId, scope.tenant())
				.eq(WmsPurchaseReceiptEntity::getFactoryCode, scope.factory())
				.eq(WmsPurchaseReceiptEntity::getId, id).last(lock ? "FOR UPDATE" : ""));
		require(source != null, "当前工厂内原采购收料单不存在");
		return source;
	}

	private PmsOrderEntity order(WmsPurchaseReceiptEntity source, boolean lock) {
		require(StrUtil.isNotBlank(source.getPmsOrderNo()) && StrUtil.isNotBlank(source.getSupplierCode()),
				"原采购收料单缺少采购订单或供应商来源");
		var candidates = orders.selectList(Wrappers.<PmsOrderEntity>lambdaQuery()
				.eq(PmsOrderEntity::getTenantId, source.getTenantId())
				.eq(PmsOrderEntity::getFactoryCode, source.getFactoryCode())
				.eq(PmsOrderEntity::getPmsOrderNo, source.getPmsOrderNo())
				.orderByAsc(PmsOrderEntity::getId).last(lock ? "LIMIT 2 FOR UPDATE" : "LIMIT 2"));
		require(candidates.size() == 1, "原采购订单不存在或在当前组织内不唯一");
		var order = candidates.get(0);
		require(Objects.equals(source.getSupplierCode(), order.getSupplierCode()), "采购收料与原订单的供应商不一致");
		return order;
	}

	private Map<Long, PmsOrderDetailEntity> orderLines(Scope scope, Long orderId, boolean lock) {
		var rows = orderLines.selectList(Wrappers.<PmsOrderDetailEntity>lambdaQuery()
				.eq(PmsOrderDetailEntity::getTenantId, scope.tenant())
				.eq(PmsOrderDetailEntity::getFactoryCode, scope.factory())
				.eq(PmsOrderDetailEntity::getPmsOrderId, orderId)
				.orderByAsc(PmsOrderDetailEntity::getId).last(lock ? "FOR UPDATE" : ""));
		require(!rows.isEmpty(), "原采购订单无有效明细");
		return rows.stream().collect(Collectors.toMap(PmsOrderDetailEntity::getId, row -> row));
	}

	private static void requireSameSource(WmsPurchaseReceiptEntity before, WmsPurchaseReceiptEntity after) {
		require(after != null && Objects.equals(before.getId(), after.getId())
						&& Objects.equals(before.getPmsOrderNo(), after.getPmsOrderNo())
						&& Objects.equals(before.getSupplierCode(), after.getSupplierCode())
						&& Objects.equals(before.getFactoryCode(), after.getFactoryCode())
						&& Objects.equals(before.getTenantId(), after.getTenantId()),
				"原采购收料单来源已变化");
	}

	/**
	 * 已指定的收料仓必须和入库目标一致。未指定收料仓时，由已提交的 WMS 原通知及过账证明确定目标。
	 */
	private static void requirePostingWarehouse(WmsPurchaseReceiptEntity receipt, String warehouseCode) {
		require(StrUtil.isNotBlank(warehouseCode) && warehouseCode.length() <= 100
						&& warehouseCode.equals(warehouseCode.trim()), "采购入库目标仓库编码无效");
		require(StrUtil.isBlank(receipt.getWarehouseCode()) || warehouseCode.equals(receipt.getWarehouseCode()),
				"入库目标仓库与原采购收料单指定的仓库不一致");
	}

	/** 混合收料可先处理已具备质量依据的批次，明细仍须逐笔核对已完成质量和已生成通知。 */
	private static void requirePostingReceiptState(WmsPurchaseReceiptEntity receipt) {
		require(READY.equals(receipt.getState()) || QUALITY_PENDING.equals(receipt.getState()),
				"原采购收料单当前状态不允许入库回写");
	}

	private Scope scope(String permission) {
		var authentication = SecurityUtils.getAuthentication();
		var user = SecurityUtils.getUser();
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		require(authentication != null && authentication.isAuthenticated() && user != null && user.getId() != null
						&& user.getId() > 0 && StrUtil.isNotBlank(user.getUsername()) && tenant != null && tenant > 0
						&& Objects.equals(tenant, user.getTenantId()) && !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
						&& !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && StrUtil.isNotBlank(factory),
				"采购入库回写要求实际人员、租户与工厂");
		var access = factories.check(new MdmFactoryAccessCheck().setUserId(user.getId())
				.setFactoryCode(factory).setRequiredPermission(permission));
		require(access != null && access.isOk() && Boolean.TRUE.equals(access.getData()), "当前人员无对应工厂权限");
		return new Scope(tenant, factory, user.getUsername());
	}

	private static BigDecimal capped(BigDecimal before, BigDecimal delta, BigDecimal limit, String origin) {
		require(before != null && before.signum() >= 0 && limit != null && limit.signum() >= 0,
				origin + "的当前累计或有效上限无法核验");
		BigDecimal total = before.add(delta);
		require(total.compareTo(limit) <= 0, origin + "的本次累计超过允许入库数量");
		return total;
	}

	private String freezePayload(PmsPurchaseInboundPosting command) {
		try {
			return json.writeValueAsString(command);
		} catch (JsonProcessingException error) {
			throw new CheckedException("采购入库原始载荷无法保存", error);
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}

	private record Scope(Long tenant, String factory, String actor) {
	}

	private record BatchChange(Long id, BigDecimal before, BigDecimal total, String state) {
	}

	private record ReceiptLineChange(Long id, BigDecimal before, BigDecimal total) {
	}

	private record PostingUpdates(List<BatchChange> batches, List<ReceiptLineChange> receiptLines,
	                              Map<Long, BigDecimal> orderDeltas, Map<Long, BigDecimal> expectedArrivals) {
	}
}
