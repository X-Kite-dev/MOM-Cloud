package com.kitex.mom.wms.service.special;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsSpecialMovementDTO.*;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import com.kitex.mom.wms.service.inBill.WmsInBillFactory;
import com.kitex.mom.wms.service.inBill.AbstractWmsInBillHandler;
import com.kitex.mom.wms.service.outBill.WmsOutBillFactory;
import com.kitex.mom.wms.service.outBill.AbstractWmsOutBillHandler;
import com.kitex.mom.wms.service.special.WmsSpecialAccess.Scope;
import com.kitex.mom.wms.util.WmsStockAvailability;
import com.kitex.mom.wms.util.WmsSerialStockGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.kitex.mom.wms.service.special.WmsSpecialRules.*;

@Service
@RequiredArgsConstructor
public class WmsSpecialStockPostingService implements WmsSpecialStockPosting {
	private final WmsLiveStockMapper stocks;
	private final WmsSpecialBillProof proof;
	private final WmsSpecialAccess access;
	private final WmsSpecialMaterialService materials;
	private final WmsSpecialRepository repository;

	@Override
	public Posted post(Scope scope, OrderDocument order, Command command, List<Flow> previous) {
		var sourceFlows = new HashMap<String, WmsInoutFlowEntity>();
		Direction direction =
				command.action() == Action.RECEIVE_OUTPUT ? Direction.IN : order.plan().direction();
		if (command.action() == Action.RETURN) {
			var directions = EnumSet.noneOf(Direction.class);
			for (var item : command.lines()) {
				var original = previous.stream()
						.filter(f -> f.id().equals(item.sourceFlowId()) && f.sourceFlowId() == null).findFirst()
						.orElseThrow(() -> new CheckedException("请选择原实际收发流水"));
				directions.add(original.direction() == Direction.IN ? Direction.OUT : Direction.IN);
				sourceFlows.put(original.id(), proof.origin(scope, original));
			}
			require(directions.size() == 1, "收回入库与退回对方请分别办理");
			direction = directions.iterator().next();
		}
		var warehouse = access.checkOperation(scope, direction, false);
		var sources = new IdentityHashMap<Object, WmsInoutFlowEntity>();
		String type = WmsSpecialBillProof.billType(order, direction);
		String billId;
		if (direction == Direction.OUT) {
			var selected = new HashMap<String, WmsLiveStockEntity>();
			for (var item : command.lines().stream().sorted(Comparator.comparingLong(i -> id(i.stockId())))
					.toList()) {
				selected.computeIfAbsent(item.stockId(), key -> stocks.selectOne(
						new LambdaQueryWrapper<WmsLiveStockEntity>().eq(WmsLiveStockEntity::getTenantId,
										scope.tenant())
								.eq(WmsLiveStockEntity::getFactoryCode, scope.factory())
								.eq(WmsLiveStockEntity::getWarehouseCode, scope.warehouse())
								.eq(WmsLiveStockEntity::getId, id(key)).last("FOR UPDATE"), false));
			}
			var details = new ArrayList<WmsOutBillDetailEntity>();
			var expectedBalances = new LinkedHashMap<String, BigDecimal>();
			for (var item : command.lines()) {
				var line = line(order, item.lineId());
				var stock = selected.get(item.stockId());
				require(stock != null && Objects.equals(stock.getTenantId(), scope.tenant()) &&
						Objects.equals(stock.getFactoryCode(), scope.factory()) &&
						Objects.equals(stock.getWarehouseCode(), scope.warehouse()) &&
						WmsStockAvailability.isUsable(stock) &&
						Objects.equals(stock.getMatCode(), line.materialCode()) &&
						Objects.equals(stock.getMatUnit(), line.unit()), "所选库存不属于原仓、原物料或已不可用");
				require(stock.getInTime() != null && stock.getMatNum() != null &&
								Double.isFinite(stock.getMatNum()) &&
								quantity(item.quantity()).compareTo(java.math.BigDecimal.valueOf(stock.getMatNum())) <=
										0,
						"所选库存数量或首次入库依据不完整");
				require(text(item.siteCode()).isEmpty() || Objects.equals(item.siteCode(), stock.getSiteCode()),
						"所选库存库位已变化，请重新选择");
				require(text(item.containerCode()).isEmpty() ||
								Objects.equals(item.containerCode(), stock.getContainerCode()),
						"所选库存容器已变化，请重新选择");
				require(text(item.batch()).isEmpty() || Objects.equals(item.batch(), stock.getMatBatch()),
						"所选库存批次已变化，请重新选择");
				require(text(item.barcode()).isEmpty() || Objects.equals(item.barcode(), stock.getMatBarcode()),
						"所选库存实物码已变化，请重新选择");
				// 同一库存可分多行办理；每一步都必须能够由现有库存字段无损保存。
				var beforeQuantity = expectedBalances.getOrDefault(item.stockId(),
						BigDecimal.valueOf(stock.getMatNum()));
				var remaining = beforeQuantity.subtract(quantity(item.quantity()));
				require(remaining.signum() >= 0 && Double.isFinite(remaining.doubleValue()) &&
								BigDecimal.valueOf(remaining.doubleValue()).compareTo(remaining) == 0,
						"本次数量无法在现有库存计量精度下精确扣减，请调整计量单位或拆分来源库存");
				expectedBalances.put(item.stockId(), remaining);
				WmsSerialStockGuard.requireWhole(stock, quantity(item.quantity()));
				var source = item.sourceFlowId() == null ? null : sourceFlows.get(item.sourceFlowId());
				if (source != null) {
					require(WmsSpecialStockProof.sameCurrentStock(WmsSpecialStockProof.identity(source), stock),
							"所选库存不是原收货形成的实物，不能按该原件退回");
				}
				var detail = new WmsOutBillDetailEntity();
				BeanUtil.copyProperties(stock, detail, "id");
				detail.setSourceStockId(stock.getId());
				detail.setMatNum(quantity(item.quantity()).doubleValue());
				detail.setBillType(type);
				detail.setExpansion(proof.detailStamp(item));
				details.add(detail);
				if (source != null) {
					sources.put(detail, source);
				}
			}
			require(warehouse != null, "实际仓库信息无法读取");
			var bill = new WmsOutBillEntity();
			bill.setTenantId(scope.tenant());
			bill.setFactoryCode(scope.factory());
			bill.setWarehouseCode(scope.warehouse());
			bill.setWarehouseName(warehouse.getWarehouseName());
			bill.setBillType(type);
			bill.setDocumentId(id(order.id()));
			bill.setDocumentTable(WmsSpecialExecutionContext.DOCUMENT);
			bill.setExpansion(proof.stamp(scope, order, command));
			bill.setRemark(remark(order, command));
			bill.setDetails(details);
			require(Boolean.TRUE.equals(WmsSpecialExecutionContext.execute(id(order.id()), type, sources,
					() -> ((AbstractWmsOutBillHandler) WmsOutBillFactory.getHandler(type)).addAndPass(bill))) &&
					bill.getId() != null, "实际出库未完成，整笔操作已回滚");
			verifyOutboundBalances(scope, selected, expectedBalances);
			billId = bill.getId().toString();
		} else {
			require(warehouse != null, "实际仓库信息无法读取");
			var details = new ArrayList<WmsInBillDetailEntity>();
			for (var item : command.lines()) {
				require(item.stockId() == null, "入库请填写实际收货身份，不指定现存库存");
				field(text(item.siteCode()), 100, true, "入库库位");
				field(text(item.containerCode()), 100, false, "容器");
				field(text(item.batch()), 200, false, "批次");
				field(text(item.barcode()), 200, false, "实物码");
				var line = line(order, item.lineId());
				materials.verifyCurrent(scope, line);
				var source = item.sourceFlowId() == null ? null : sourceFlows.get(item.sourceFlowId());
				var detail = new WmsInBillDetailEntity();
				detail.setTenantId(scope.tenant());
				detail.setFactoryCode(scope.factory());
				detail.setWarehouseCode(scope.warehouse());
				detail.setWarehouseName(warehouse.getWarehouseName());
				detail.setMatCode(line.materialCode());
				detail.setMatName(line.materialName());
				detail.setMatUnit(line.unit());
				detail.setMatNum(quantity(item.quantity()).doubleValue());
				detail.setStockOwnerType("OWNED");
				detail.setStockOwnerCode("");
				detail.setSourceBusinessNo("SF" + order.id());
				detail.setIsQualityTest("0");
				if (source != null) {
					require(order.rule().quarantineReturns() ||
									Boolean.TRUE.equals(item.originalConditionConfirmed()),
							"按原状态收回须确认原实物状态，异常物料应待检隔离");
					require(text(item.batch()).isEmpty() || Objects.equals(item.batch(), source.getMatBatch()),
							"退回批次不能替换原件");
					require(text(item.barcode()).isEmpty() ||
									Objects.equals(item.barcode(), source.getMatBarcode()),
							"退回实物码不能替换原件");
					WmsSpecialStockProof.copySource(source, detail);
					sources.put(detail, source);
				} else {
					detail.setMatBatch(text(item.batch()));
					detail.setMatBarcode(text(item.barcode()));
					detail.setMatProduceDate(date(item.producedAt()));
					detail.setMatValidEndDate(date(item.expiresAt()));
					if (detail.getMatProduceDate() != null && detail.getMatValidEndDate() != null) {
						long days = ChronoUnit.DAYS.between(detail.getMatProduceDate(),
								detail.getMatValidEndDate());
						require(days >= 0 && days <= Integer.MAX_VALUE, "生产日期与有效期不一致");
						detail.setMatValidPeriod((int) days);
					}
				}
				detail.setSiteCode(item.siteCode());
				detail.setContainerCode(text(item.containerCode()));
				detail.setExpansion(proof.detailStamp(item));
				details.add(detail);
			}
			var bill = new WmsInBillEntity();
			bill.setTenantId(scope.tenant());
			bill.setFactoryCode(scope.factory());
			bill.setWarehouseCode(scope.warehouse());
			bill.setWarehouseName(warehouse.getWarehouseName());
			bill.setBillType(type);
			bill.setDocumentId(id(order.id()));
			bill.setDocumentTable(WmsSpecialExecutionContext.DOCUMENT);
			bill.setExpansion(proof.stamp(scope, order, command));
			bill.setRemark(remark(order, command));
			bill.setDetails(details);
			require(Boolean.TRUE.equals(
					WmsSpecialExecutionContext.execute(id(order.id()), type, sources,
							order.rule().quarantineReturns(),
							() -> ((AbstractWmsInBillHandler) WmsInBillFactory.getHandler(type)).addAndPass(
									bill))) &&
					bill.getId() != null, "实际入库未完成，整笔操作已回滚");
			billId = bill.getId().toString();
		}
		return proof.read(scope, order, command, billId, direction, scope.operator());
	}

	void verifyOutboundBalances(Scope scope, Map<String, WmsLiveStockEntity> selected,
	                            Map<String, BigDecimal> expected) {
		for (var entry : expected.entrySet()) {
			var actual = stocks.selectById(id(entry.getKey()));
			if (entry.getValue().signum() == 0) {
				require(actual == null, "实际库存未按本次扣减结清，整笔操作已回滚");
				continue;
			}
			require(actual != null && Objects.equals(actual.getTenantId(), scope.tenant()) &&
							Objects.equals(actual.getFactoryCode(), scope.factory()) &&
							Objects.equals(actual.getWarehouseCode(), scope.warehouse()) &&
							WmsStockAvailability.isUsable(actual) && actual.getMatNum() != null &&
							Double.isFinite(actual.getMatNum()) &&
							BigDecimal.valueOf(actual.getMatNum()).compareTo(entry.getValue()) == 0,
					"实际库存余额与本次精确扣减不一致，整笔操作已回滚");
			WmsSpecialStockProof.matches(WmsSpecialStockProof.identity(selected.get(entry.getKey())), actual);
		}
	}

	private Line line(OrderDocument order, String lineId) {
		return allLines(order.plan()).stream().filter(l -> l.lineId().equals(lineId)).findFirst()
				.orElseThrow(() -> new CheckedException("作业明细不属于原申请"));
	}

	private LocalDate date(String value) {
		if (value == null || value.isEmpty()) {
			return null;
		}
		try {
			return LocalDate.parse(value);
		} catch (Exception e) {
			throw new CheckedException("生产日期或有效期格式无效");
		}
	}

	private String remark(OrderDocument order, Command command) {
		String value = "特殊收发 SF" + order.id() + "：" + text(command.reason());
		return value.length() > 500 ? value.substring(0, 500) : value;
	}

	@Override
	public void verify(Scope scope, Receipt receipt) {
		var order = repository.order(scope, receipt.orderId());
		require(order != null, "原申请未找到，不能核对实际记账凭据");
		var direction = receipt.flows().get(0).direction();
		var actual = proof.read(scope, order, receipt.original(), receipt.billId(), direction,
				receipt.operatorCode());
		require(Objects.equals(actual.billNo(), receipt.billNo()) &&
						Objects.equals(actual.flows(), receipt.flows()),
				"原永久凭据与实际收发事实不一致，请核对原单");
	}
}
