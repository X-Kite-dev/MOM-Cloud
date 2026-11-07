package com.kitex.mom.wms.service.special;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.mom.wms.dto.WmsQualityStatusCommand;
import com.kitex.mom.wms.dto.WmsSpecialMovementDTO.*;
import com.kitex.mom.wms.dto.WmsSpecialReturnQualityDTO.*;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.entity.WmsQualityStatusOperationEntity;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.WmsMaterialReservationService;
import com.kitex.mom.wms.service.WmsSalesStockReservationService;
import com.kitex.mom.wms.service.special.WmsSpecialAccess.Scope;
import com.kitex.mom.wms.util.WmsSerialStockGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import static com.kitex.mom.wms.service.special.WmsSpecialRules.*;

/**
 * 以永久RETURN、正式单据和质量拆分账证明精确原实物；不使用批次匹配或FIFO替代来源证明。
 * 所有写分支在调用方READ_COMMITTED事务内串行锁原仓、原请求和原root，再锁精确库存。
 */
@Service
@RequiredArgsConstructor
public class WmsSpecialReturnQualityProofService {
	private final WmsSpecialAccess access;
	private final WmsSpecialRepository repository;
	private final WmsSpecialStockPosting posting;
	private final WmsSpecialBillProof billProof;
	private final WmsLiveStockMapper stocks;
	private final WmsQualityStatusOperationMapper operations;
	private final WmsInBillMapper inBills;
	private final WmsInBillDetailMapper inDetails;
	private final WmsMaterialReservationService reservations;
	private final WmsSalesStockReservationService salesReservations;
	private final JdbcTemplate db;
	private final ObjectMapper json = new ObjectMapper();
	private static final Set<String> DONE = Set.of("SUCCESS", "ACTION_PENDING", "EXECUTED");
	private static final Set<String> MUTABLE_QUALITY = Set.of("qualityResult", "qualityStatus", "qualityHoldNo",
			"qualitySourceType", "qualitySourceId", "status");

	/**
	 * 新能力仅在V4专用结构及保护实际安装后启用，普通质量路径不调用此检查。
	 */
	private void ready() {
		try {
			require(Boolean.TRUE.equals(db.queryForObject(
							"SELECT mom_wms.wms_special_return_quality_ready_v1()", Boolean.class)),
					"特殊退回质量接管数据库前置未就绪");
		} catch (org.springframework.dao.DataAccessException e) {
			throw new com.kitex.common.core.exception.CheckedException("特殊退回质量接管数据库前置未安装或形状不符");
		}
	}

	private Scope scope(String warehouse, boolean write) {
		ready();
		var scope = access.scope(warehouse, write);
		access.snapshot(scope);
		return scope;
	}

	/**
	 * 在真实人员的工厂、仓库读取授权内列举候选，超过扫描边界明确拒绝。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public CandidatePage candidates(CandidateQuery query) {
		require(query != null && query.page() > 0 && query.page() <= 10000 && query.size() > 0 &&
				query.size() <= 50, "特殊退回候选分页参数无效");
		var scope = scope(query.warehouseCode(), false);
		repository.lockWarehouse(scope);
		String order = text(query.orderId());
		if (!order.isEmpty()) {
			id(order);
		}
		String reference = text(query.reference());
		field(reference, 200, false, "原申请或退回单查询条件");
		var receipts = db.query("SELECT o.result_json::text FROM mom_wms.wms_special_operation o " +
						"JOIN mom_wms.wms_special_order d ON d.id=o.order_id AND d.tenant_id=o.tenant_id " +
						"AND d.factory_code=o.factory_code AND d.warehouse_code=o.warehouse_code " +
						"WHERE o.tenant_id=? AND o.factory_code=? AND o.warehouse_code=? " +
						"AND o.action='RETURN' AND o.status='REGISTERED' " +
						"AND d.plan_json->>'direction'='OUT' AND d.rule_json->>'quarantineReturns'='true' " +
						"AND (?='' OR o.order_id::text=?) AND (?='' OR d.plan_json->>'reference' ILIKE ? " +
						"OR o.result_json->>'billNo' ILIKE ?) ORDER BY o.id DESC LIMIT 501",
				(r, n) -> repository.decode(r.getString(1), Receipt.class), scope.tenant(), scope.factory(),
				scope.warehouse(), order, order, reference, "%" + reference + "%", "%" + reference + "%");
		require(receipts.size() <= 500, "退回凭据超过500条，请按原申请或业务参照缩小候选范围");
		var candidates = new ArrayList<Candidate>();
		for (var receipt : receipts) {
			// SQL已排除明确不适用的冻结业务类别；合格类别的破损凭据必须严格报错，不能吞成空候选。
			var orderDocument = proveReturn(scope, receipt);
			for (var flow : receipt.flows()) {
				var allowed = isolatedRemainders(scope, receipt, flow);
				for (String stockId : allowed.keySet()) {
					var stock = stock(scope, stockId, false);
					if (stock == null || !isolated(stock, orderDocument.id())) {
						continue;
					}
					// 只有原隔离实物可供选取；预留的隔离行不会成为候选。
					if (reserved(stock.getId()).signum() != 0) {
						continue;
					}
					var target = target(scope, receipt, flow, stock, allowed.get(stockId), number(amount(stock)));
					candidates.add(new Candidate(target, orderDocument.plan().reference(), receipt.billNo(),
							orderDocument.plan().kind().name()));
				}
			}
		}
		int from = Math.min(candidates.size(), (query.page() - 1) * query.size());
		return new CandidatePage(List.copyOf(candidates.subList(from,
				Math.min(candidates.size(), from + query.size()))), query.page(), query.size(), candidates.size());
	}

	/**
	 * 按候选指纹重新证明当前实物；不把客户端传入的快照当作权威来源。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Target preflight(Selection selection) {
		require(selection != null, "请选择特殊退回实物");
		var scope = scope(selection.warehouseCode(), false);
		repository.lockWarehouse(scope);
		return selected(scope, selection, false);
	}

	private Target selected(Scope scope, Selection selection, boolean lock) {
		id(selection.orderId());
		id(selection.returnFlowId());
		id(selection.stockId());
		field(selection.returnRequestId(), 200, true, "退回原请求号");
		var receipt = repository.operation(scope, selection.returnRequestId());
		require(receipt != null && Objects.equals(receipt.orderId(), selection.orderId()), "退回原请求或申请不匹配");
		proveReturn(scope, receipt);
		var flow = receipt.flows().stream().filter(f -> Objects.equals(f.id(), selection.returnFlowId()))
				.findFirst()
				.orElseThrow(() -> new com.kitex.common.core.exception.CheckedException("退回流水不属于原凭据"));
		var allowed = isolatedRemainders(scope, receipt, flow);
		require(allowed.containsKey(selection.stockId()), "所选库存不是原RETURN或可证隔离余量");
		var stock = stock(scope, selection.stockId(), lock);
		require(stock != null, "所选隔离实物已不存在");
		var result = target(scope, receipt, flow, stock, allowed.get(selection.stockId()), selection.quantity());
		require(Objects.equals(result.fingerprint(), selection.fingerprint()), "原件、位置或数量已变化，请重新选择");
		return result;
	}

	/**
	 * 分别证明真实RETURN和原正向flow，首次来源单据另行读取，绝不混同本次入库单。
	 */
	private OrderDocument proveReturn(Scope scope, Receipt receipt) {
		require(receipt.contractVersion() == 1 && "REGISTERED".equals(receipt.status()) &&
						receipt.action() == Action.RETURN && Objects.equals(receipt.tenantId(), scope.tenant().toString()) &&
						Objects.equals(receipt.factoryCode(), scope.factory()) &&
						Objects.equals(receipt.warehouseCode(), scope.warehouse()) && receipt.original() != null &&
						Objects.equals(receipt.requestId(), receipt.original().requestId()) &&
						receipt.original().action() == Action.RETURN &&
						Objects.equals(receipt.original().orderId(), receipt.orderId()) &&
						Objects.equals(receipt.original().warehouseCode(), scope.warehouse()) && receipt.flows() != null &&
						!receipt.flows().isEmpty() &&
						receipt.flows().stream().allMatch(f -> f.direction() == Direction.IN && f.sourceFlowId() != null),
				"不是当前组织真实已登记的特殊RETURN入库凭据");
		var order = repository.order(scope, receipt.orderId());
		require(order != null && order.plan().direction() == Direction.OUT && order.rule().quarantineReturns(),
				"原申请不是隔离收回业务");
		posting.verify(scope, receipt);
		var previous = repository.operations(scope, order.id());
		for (var returned : receipt.flows()) {
			var sourceReceipts = previous.stream().filter(r -> "REGISTERED".equals(r.status()) &&
					r.action() == Action.EXECUTE && r.flows().stream().anyMatch(f ->
					Objects.equals(f.id(), returned.sourceFlowId()) && f.sourceFlowId() == null &&
							f.direction() == Direction.OUT)).toList();
			require(sourceReceipts.size() == 1, "原正向出库凭据缺失或重复");
			var sourceReceipt = sourceReceipts.get(0);
			require(Objects.equals(sourceReceipt.tenantId(), receipt.tenantId()) &&
					Objects.equals(sourceReceipt.factoryCode(), receipt.factoryCode()) &&
					Objects.equals(sourceReceipt.warehouseCode(), receipt.warehouseCode()) &&
					Objects.equals(sourceReceipt.orderId(), receipt.orderId()), "原出库凭据跨组织或申请");
			posting.verify(scope, sourceReceipt);
			var source = sourceReceipt.flows().stream().filter(f -> f.id().equals(returned.sourceFlowId()))
					.findFirst().orElseThrow();
			var sourceFlow = billProof.origin(scope, source);
			var actualReturn = billProof.origin(scope, returned);
			require(Objects.equals(source.lineId(), returned.lineId()) &&
					quantity(returned.quantity()).compareTo(quantity(source.quantity())) <= 0 &&
					Objects.equals(actualReturn.getSourceFlowId(), sourceFlow.getId()) &&
					"SPECIAL_RETURN".equals(actualReturn.getStockProofType()), "退回不是原正向实物来源");
			// RETURN恢复首次来源与库龄，质量隔离字段可与原正向不同。
			var sourceIdentity = new TreeMap<>(source.identity());
			// 隔离收回合法地把原免检标志改为待质量处理，物理和首次来源仍须逐项相同。
			sourceIdentity.put("isQualityTest", returned.identity().get("isQualityTest"));
			assertPhysical(sourceIdentity, returned.identity());
			require(!Objects.equals(receipt.billId(), returned.identity().get("sourceInBillId")),
					"首次来源不能冒用本次RETURN入库单");
			firstSource(scope, returned.identity());
		}
		return order;
	}

	private void firstSource(Scope scope, Map<String, String> identity) {
		var bill = inBills.selectById(id(identity.get("sourceInBillId")));
		var detail = inDetails.selectById(id(identity.get("sourceInBillDetailId")));
		require(bill != null && detail != null && Objects.equals(bill.getTenantId(), scope.tenant()) &&
						Objects.equals(detail.getTenantId(), scope.tenant()) &&
						Objects.equals(bill.getFactoryCode(), scope.factory()) &&
						Objects.equals(detail.getFactoryCode(), scope.factory()) && "2".equals(bill.getState()) &&
						Objects.equals(detail.getBillId(), bill.getId()) &&
						Objects.equals(detail.getMatCode(), identity.get("matCode")) &&
						Objects.equals(detail.getMatUnit(), identity.get("matUnit")),
				"首次正式入库单、明细或组织物料无法独立证明");
	}

	private Target target(Scope scope, Receipt receipt, Flow flow, WmsLiveStockEntity stock,
	                      BigDecimal expected, String requested) {
		require(isolated(stock, receipt.orderId()), "所选库存不是原SPECIAL-RETURN精确ISOLATED原件");
		require(amount(stock).compareTo(expected) == 0, "隔离余量与永久拆分数量不一致");
		WmsSpecialStockProof.matches(flow.identity(), stock);
		require(reserved(stock.getId()).signum() == 0, "所选隔离实物仍有有效预留");
		var take = quantity(requested);
		require(take.compareTo(amount(stock)) <= 0, "接管数量超过原隔离实物数量");
		WmsSerialStockGuard.requireWhole(stock, take);
		var identity = new TreeMap<>(WmsSpecialStockProof.identity(stock));
		identity.put("status", String.valueOf(stock.getStatus()));
		var target = new Target(1, scope.tenant().toString(), scope.factory(), scope.warehouse(),
				receipt.orderId(), receipt.requestId(), flow.id(), flow.sourceFlowId(), flow.stockId(),
				stock.getId().toString(), receipt.billId(), flow.billDetailId(), number(amount(stock)), number(take),
				stock.getMatUnit(), stock.getSiteCode(), text(stock.getContainerCode()),
				Collections.unmodifiableMap(identity), null);
		return withFingerprint(target, fingerprint(target));
	}

	private Target withFingerprint(Target t, String fingerprint) {
		return new Target(t.contractVersion(), t.tenantId(), t.factoryCode(), t.warehouseCode(), t.orderId(),
				t.returnRequestId(), t.returnFlowId(), t.sourceFlowId(), t.returnStockId(), t.stockId(), t.billId(),
				t.billDetailId(), t.stockQuantity(), t.quantity(), t.unit(), t.siteCode(), t.containerCode(),
				t.identity(), fingerprint);
	}

	/**
	 * 指纹使用固定字段顺序、排序身份及库存全量，不随本案部分接管量变化。
	 */
	private String fingerprint(Target t) {
		try {
			var canonical = new Target(t.contractVersion(), t.tenantId(), t.factoryCode(), t.warehouseCode(),
					t.orderId(), t.returnRequestId(), t.returnFlowId(), t.sourceFlowId(), t.returnStockId(),
					t.stockId(),
					t.billId(), t.billDetailId(), t.stockQuantity(), t.stockQuantity(), t.unit(), t.siteCode(),
					t.containerCode(), new TreeMap<>(t.identity()), null);
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(repository.encode(canonical).getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException("特殊退回原件指纹无法生成", e);
		}
	}

	/**
	 * 初次HOLD只扩展永久账实际新增的ISOLATED余量链。被接管的原ID不会作为余量再次入选。
	 * 每步同时核parent之前量=接管量+remaining；只按原仓锁内数据库永久办理序号排序，不用雪花ID或物理时间。
	 */
	private Map<String, BigDecimal> isolatedRemainders(Scope scope, Receipt receipt, Flow flow) {
		var allowed = new LinkedHashMap<String, BigDecimal>();
		allowed.put(flow.stockId(), quantity(flow.quantity()));
		for (var operation : initialHoldOperations(scope, receipt.requestId(), flow.id())) {
			var binding = decode(operation.getSpecialReturnBindingJson());
			if (!"HOLD".equals(operation.getAction()) || binding.rootHoldOperationId() != null ||
					binding.reholdOperationId() != null ||
					!Objects.equals(binding.target().returnFlowId(), flow.id()) ||
					!Objects.equals(binding.target().returnRequestId(), receipt.requestId()) ||
					!DONE.contains(operation.getStatus())) {
				continue;
			}
			var target = binding.target();
			require(Objects.equals(target.returnStockId(), flow.stockId()) &&
							allowed.containsKey(target.stockId()) &&
							allowed.get(target.stockId()).compareTo(quantity(target.stockQuantity())) == 0 &&
							ids(operation.getAffectedStockIds()).equals(Set.of(target.stockId())) &&
							operation.getAffectedQuantity().compareTo(quantity(target.quantity())) == 0,
					"特殊接管原件链不连续或数量不守恒");
			allowed.remove(target.stockId());
			var links = links(operation);
			var remaining = quantity(target.stockQuantity()).subtract(quantity(target.quantity()));
			if (remaining.signum() == 0) {
				require(links.isEmpty(), "整行接管存在不明隔离余量");
			} else {
				require(links.size() == 1 && Objects.equals(links.get(0).get("parentStockId"), target.stockId()) &&
								quantity(links.get(0).get("remainderQuantity")).compareTo(remaining) == 0,
						"初接管隔离余量缺少精确永久拆分证明");
				require(allowed.putIfAbsent(links.get(0).get("remainderStockId"), remaining) == null,
						"隔离余量库存身份重复");
			}
		}
		return allowed;
	}

	/**
	 * 验证绑定及当前人员权限，在原号查找前取得同租户请求和原仓锁，避免同号并发新建。
	 */
	public void authorizeCommand(WmsQualityStatusCommand command) {
		var binding = command.getSpecialReturnBinding();
		if (binding == null) {
			// 本案一旦有特殊原件root，后续不能去掉绑定退回普通匹配路径；普通案件不新增ready检查。
			if ("QMS_NCR".equals(command.getSourceType())) {
				Long bound = operations.selectCount(new LambdaQueryWrapper<WmsQualityStatusOperationEntity>()
						.eq(WmsQualityStatusOperationEntity::getFactoryCode, command.getFactoryCode())
						.eq(WmsQualityStatusOperationEntity::getSourceType, "QMS_NCR")
						.eq(WmsQualityStatusOperationEntity::getSourceId, command.getSourceId())
						.isNotNull(WmsQualityStatusOperationEntity::getSpecialReturnBindingJson));
				require(bound == 0, "特殊退回案件后续命令必须携永久原件绑定");
			}
			return;
		}
		var t = binding.target();
		require(t != null && t.contractVersion() == 1 && t.identity() != null, "特殊退回冻结目标不完整");
		var scope = scope(t.warehouseCode(), true);
		validateTarget(scope, t);
		require("QMS_NCR".equals(command.getSourceType()) &&
						Objects.equals(command.getFactoryCode(), t.factoryCode()) &&
						Objects.equals(command.getWarehouseCode(), t.warehouseCode()) &&
						Objects.equals(command.getMatCode(), t.identity().get("matCode")) &&
						Objects.equals(text(command.getMatBatch()), text(t.identity().get("matBatch"))) &&
						Objects.equals(command.getMatUnit(), t.unit()) &&
						Objects.equals(text(command.getSupplierCode()), text(t.identity().get("supplierCode"))) &&
						Objects.equals(text(command.getSiteCode()), text(t.siteCode())) &&
						Objects.equals(text(command.getContainerCode()), text(t.containerCode())),
				"质量命令组织、物料、单位或位置与冻结实物不一致");
		id(command.getSourceId());
		require(command.getQuantity().compareTo(quantity(t.quantity())) <= 0, "本次质量数量超过冻结接管量");
		if (binding.rootHoldOperationId() == null) {
			require("HOLD".equals(command.getAction()) && binding.reholdOperationId() == null &&
							command.getQuantity().compareTo(quantity(t.quantity())) == 0,
					"初次接管须使用完整冻结数量及HOLD命令");
		} else {
			id(binding.rootHoldOperationId());
			require(!"HOLD".equals(command.getAction()) || binding.reholdOperationId() != null,
					"重隔离必须绑定本分类原处置事务");
			if (binding.reholdOperationId() != null) {
				id(binding.reholdOperationId());
				require("HOLD".equals(command.getAction()), "原处置引用仅用于验证驳回重隔离");
			}
		}
		repository.lockRequest(scope, "QUALITY:" + command.getRequestId());
		repository.lockWarehouse(scope);
	}

	private void validateTarget(Scope scope, Target t) {
		require(Objects.equals(t.tenantId(), scope.tenant().toString()) &&
						Objects.equals(t.factoryCode(), scope.factory()) &&
						Objects.equals(t.warehouseCode(), scope.warehouse()),
				"冻结原件不属于当前实际租户、工厂或仓库");
		for (String value : Arrays.asList(t.orderId(), t.returnFlowId(), t.sourceFlowId(), t.returnStockId(),
				t.stockId(), t.billId(), t.billDetailId())) {
			id(value);
		}
		quantity(t.stockQuantity());
		require(quantity(t.quantity()).compareTo(quantity(t.stockQuantity())) <= 0 &&
				Objects.equals(t.fingerprint(), fingerprint(t)) &&
				"ISOLATED".equals(t.identity().get("qualityStatus")) &&
				"1".equals(t.identity().get("status")) &&
				("SPECIAL-RETURN:" + t.orderId()).equals(t.identity().get("qualityHoldNo")) &&
				"WMS_SPECIAL_RETURN".equals(t.identity().get("qualitySourceType")) &&
				Objects.equals(t.orderId(), t.identity().get("qualitySourceId")), "冻结目标指纹或原隔离依据无效");
	}

	/**
	 * 已保存事务也重新核当前实名/工厂/原仓权限；成功重放不重新选择今日库存。
	 */
	public void authorizeOperation(WmsQualityStatusOperationEntity operation) {
		if (operation.getSpecialReturnBindingJson() == null) {
			return;
		}
		var b = decode(operation.getSpecialReturnBindingJson());
		var scope = scope(b.target().warehouseCode(), true);
		validateTarget(scope, b.target());
		require(Objects.equals(operation.getTenantId(), scope.tenant()) &&
				Objects.equals(operation.getFactoryCode(), scope.factory()), "原质量事务不属于当前实际组织");
		repository.lockRequest(scope, "QUALITY:" + operation.getRequestId());
		repository.lockWarehouse(scope);
	}

	/**
	 * 初HOLD重读准确旧隔离；后续仅从本root或原明细处置片段的可证后裔取库存。
	 */
	public List<WmsLiveStockEntity> resolve(WmsQualityStatusOperationEntity operation) {
		authorizeOperation(operation);
		var binding = decode(operation.getSpecialReturnBindingJson());
		var t = binding.target();
		var scope = scope(t.warehouseCode(), true);
		if (binding.rootHoldOperationId() == null) {
			var actual = selected(scope, new Selection(t.warehouseCode(), t.orderId(), t.returnRequestId(),
					t.returnFlowId(), t.stockId(), t.quantity(), t.fingerprint()), true);
			require(t.equals(actual), "初次HOLD冻结原文与真实原件已漂移");
			return List.of(Objects.requireNonNull(stock(scope, t.stockId(), true)));
		}
		var root = operation(scope, binding.rootHoldOperationId());
		var rootBinding = decode(root.getSpecialReturnBindingJson());
		require("HOLD".equals(root.getAction()) && "SUCCESS".equals(root.getStatus()) &&
				rootBinding.rootHoldOperationId() == null && rootBinding.reholdOperationId() == null &&
				t.equals(rootBinding.target()), "首次接管操作与冻结原件不匹配");
		assertCase(operation, root);
		require(root.getSpecialReturnSequence() < operation.getSpecialReturnSequence(),
				"后续处置必须晚于本案首次HOLD永久办理序号");
		var all = rootOperations(scope, root.getId().toString());
		var allowed = new LinkedHashSet<>(ids(root.getAffectedStockIds()));
		WmsQualityStatusOperationEntity rejected = null;
		if (binding.reholdOperationId() != null) {
			rejected = operation(scope, binding.reholdOperationId());
			// 新原位返工协议独占REWORK恢复；旧已完成回执仍可读，迟到PENDING不能抢回原件。
			require(!"REWORK".equals(rejected.getAction()), "返工须走原位完成/独立复核，不能使用旧重隔离入口");
			assertCase(operation, rejected);
			require(rejected.getSpecialReturnSequence() > root.getSpecialReturnSequence() &&
							rejected.getSpecialReturnSequence() < operation.getSpecialReturnSequence(),
					"验证驳回必须引用本案此前真实完成的处置");
			require(supersededByOperationId(rejected) == null,
					"原处置已成功重隔离，请沿原稳定请求恢复，不能再次引用旧处置");
			var rb = decode(rejected.getSpecialReturnBindingJson());
			require(Objects.equals(rb.rootHoldOperationId(), root.getId().toString()) &&
							t.equals(rb.target()) &&
							Set.of("RELEASE", "CONCESSION", "REWORK", "SCRAP", "RETURN")
									.contains(rejected.getAction()) &&
							Set.of("SUCCESS", "ACTION_PENDING").contains(rejected.getStatus()) &&
							rejected.getExecutedTime() == null &&
							rejected.getAffectedQuantity().compareTo(operation.getRequestedQuantity()) == 0,
					"验证驳回原处置不属于本片段、数量不同或已执行，不能恢复库存");
		}
		// 首HOLD的ISOLATED余量不属于本案；按数据库永久办理序号扩展同root正式拆分后裔。
		for (var prior : all) {
			if (prior.getId().equals(operation.getId()) || !DONE.contains(prior.getStatus())) {
				continue;
			}
			var pb = decode(prior.getSpecialReturnBindingJson());
			if (!Objects.equals(pb.rootHoldOperationId(), root.getId().toString())) {
				continue;
			}
			assertCase(operation, prior);
			require(prior.getSpecialReturnSequence() < operation.getSpecialReturnSequence(),
					"本待办之后已产生其他永久处置，请核对原片段后恢复");
			require(t.equals(pb.target()) && allowed.containsAll(ids(prior.getAffectedStockIds())),
					"本root后续质量处置包含不可证明库存");
			for (var link : links(prior)) {
				require(allowed.contains(link.get("parentStockId")), "本root拆分父库存无来源证明");
				allowed.add(link.get("remainderStockId"));
			}
		}
		if (rejected != null) {
			var fragment = new LinkedHashSet<>(ids(rejected.getAffectedStockIds()));
			require(allowed.containsAll(fragment), "原处置片段不属于首次接管实物");
			// 原ID会被多次保留：只沿原处置之后的split，不能吸入原处置之前或本次剩余片段。
			for (var prior : all) {
				if (prior.getSpecialReturnSequence() <= rejected.getSpecialReturnSequence() ||
						!DONE.contains(prior.getStatus())) {
					continue;
				}
				var pb = decode(prior.getSpecialReturnBindingJson());
				if (Objects.equals(pb.rootHoldOperationId(), root.getId().toString())) {
					for (var link : links(prior)) {
						if (fragment.contains(link.get("parentStockId"))) {
							fragment.add(link.get("remainderStockId"));
						}
					}
				}
			}
			allowed.retainAll(fragment);
		}
		var result = new ArrayList<WmsLiveStockEntity>();
		String rejectedState = rejected == null ? null : switch (rejected.getAction()) {
			case "RELEASE" -> "QUALIFIED";
			case "CONCESSION" -> "CONCESSION";
			case "REWORK" -> "REWORK";
			default -> rejected.getAction() + "_PENDING";
		};
		for (var stock : stocks.selectList(new LambdaQueryWrapper<WmsLiveStockEntity>()
				.eq(WmsLiveStockEntity::getTenantId, scope.tenant())
				.eq(WmsLiveStockEntity::getFactoryCode, scope.factory())
				.eq(WmsLiveStockEntity::getWarehouseCode, scope.warehouse())
				.in(WmsLiveStockEntity::getId, allowed.stream().map(WmsSpecialRules::id).toList())
				.orderByAsc(WmsLiveStockEntity::getId).last("FOR UPDATE"))) {
			// 新分类只消耗尚未处置的HOLD；既有REWORK只能由绑定原处置的恢复分支处理。
			boolean state = rejected == null ? "HOLD".equals(stock.getQualityStatus()) :
					Objects.equals(stock.getQualityStatus(), rejectedState);
			if (!state || stock.getQualityReworkOperationId() != null) {
				continue;
			}
			require(Objects.equals(stock.getQualityHoldNo(), operation.getHoldNo()) &&
							Objects.equals(stock.getQualitySourceType(), "QMS_NCR") &&
							Objects.equals(stock.getQualitySourceId(), operation.getSourceId()),
					"本片段库存已由其他案件接管");
			assertPhysical(t.identity(), WmsSpecialStockProof.identity(stock));
			require(Objects.equals(text(stock.getSiteCode()), text(t.siteCode())) &&
					Objects.equals(text(stock.getContainerCode()), text(t.containerCode())) &&
					reserved(stock.getId()).signum() == 0, "本片段位置漂移或存在预留");
			result.add(stock);
		}
		require(!result.isEmpty(), "本案精确处置片段已变化，不能重新匹配同批库存");
		if (rejected != null) {
			require(result.stream().map(this::amount).reduce(BigDecimal.ZERO, BigDecimal::add)
							.compareTo(operation.getRequestedQuantity()) == 0,
					"原处置精确片段数量已变化，禁止借用另一分类仍HOLD量");
		}
		return result;
	}

	/**
	 * NCR返工专用纯来源证明，不调用特殊收发scope/岗位或获取额外锁。
	 * 调用方已经核真实工厂及quality仓权；此处仅核395永久Target/root/parent与撤销关系。
	 */
	public void validateNcrReworkBinding(Binding binding, WmsQualityStatusOperationEntity root,
	                                     WmsQualityStatusOperationEntity parent) {
		ready();
		var target = binding.target();
		var actor = com.kitex.common.security.util.SecurityUtils.getUser();
		var actual = new Scope(parent.getTenantId(), parent.getFactoryCode(), target.warehouseCode(),
				actor.getUsername(), actor.getId());
		validateTarget(actual, target);
		var rb = decode(root.getSpecialReturnBindingJson());
		var pb = decode(parent.getSpecialReturnBindingJson());
		require(rb != null && pb != null && rb.rootHoldOperationId() == null && rb.reholdOperationId() == null
						&& target.equals(rb.target()) && binding.equals(pb)
						&& root.getId().toString().equals(binding.rootHoldOperationId()) &&
						binding.reholdOperationId() == null
						&& root.getSpecialReturnSequence() != null && parent.getSpecialReturnSequence() != null
						&& root.getSpecialReturnSequence() < parent.getSpecialReturnSequence(),
				"特殊返工原件或永久先后关系不匹配");
		assertCase(parent, root);
		var replaced = operations.selectList(new LambdaQueryWrapper<WmsQualityStatusOperationEntity>()
				.eq(WmsQualityStatusOperationEntity::getTenantId, parent.getTenantId())
				.eq(WmsQualityStatusOperationEntity::getFactoryCode, parent.getFactoryCode())
				.eq(WmsQualityStatusOperationEntity::getWarehouseCode, target.warehouseCode())
				.eq(WmsQualityStatusOperationEntity::getAction, "HOLD")
				.eq(WmsQualityStatusOperationEntity::getStatus, "SUCCESS")
				.apply("special_return_binding_json::jsonb ->> 'reholdOperationId' = {0}",
						parent.getId().toString())
				.last("LIMIT 1"));
		require(replaced.isEmpty(), "该特殊返工parent已被旧重隔离永久替代，需按原历史治理");
	}

	/** 复用395物理身份空值规则，仅比较原件，不引入specialMovement岗位授权。 */
	public void validateNcrReworkPiece(Target target, Map<String, String> identity,
	                                   String warehouse, String site, String container) {
		assertPhysical(target.identity(), identity);
		require(Objects.equals(target.warehouseCode(), warehouse) &&
				Objects.equals(text(target.siteCode()), text(site))
				&& Objects.equals(text(target.containerCode()), text(container)), "特殊返工原件位置已漂移");
	}

	private void assertCase(WmsQualityStatusOperationEntity expected, WmsQualityStatusOperationEntity actual) {
		require(Objects.equals(expected.getTenantId(), actual.getTenantId()) &&
				Objects.equals(expected.getFactoryCode(), actual.getFactoryCode()) &&
				Objects.equals(expected.getWarehouseCode(), actual.getWarehouseCode()) &&
				"QMS_NCR".equals(actual.getSourceType()) &&
				Objects.equals(expected.getSourceId(), actual.getSourceId()) &&
				Objects.equals(expected.getHoldNo(), actual.getHoldNo()), "质量操作不属于同一NCR及原隔离root");
	}

	/**
	 * 原件链在SQL中先按冻结RETURN请求/流水筛选，只读取初次接管的永久证据。
	 */
	private List<WmsQualityStatusOperationEntity> initialHoldOperations(Scope scope, String request, String flow) {
		return completeOperations(operationScope(scope)
				.apply("special_return_binding_json::jsonb #>> '{target,returnRequestId}' = {0}", request)
				.apply("special_return_binding_json::jsonb #>> '{target,returnFlowId}' = {0}", flow)
				.apply("(special_return_binding_json::jsonb ->> 'rootHoldOperationId') IS NULL"), "原RETURN隔离余量链");
	}

	/**
	 * 本root及后续处置先在SQL中筛选，其他原件/案件的永久历史不占本案读取上限。
	 */
	private List<WmsQualityStatusOperationEntity> rootOperations(Scope scope, String root) {
		return completeOperations(operationScope(scope).and(q -> q.eq(WmsQualityStatusOperationEntity::getId, id(root))
						.or().apply("special_return_binding_json::jsonb ->> 'rootHoldOperationId' = {0}", root)),
				"本root处置链");
	}

	private LambdaQueryWrapper<WmsQualityStatusOperationEntity> operationScope(Scope scope) {
		return new LambdaQueryWrapper<WmsQualityStatusOperationEntity>()
				.eq(WmsQualityStatusOperationEntity::getTenantId, scope.tenant())
				.eq(WmsQualityStatusOperationEntity::getFactoryCode, scope.factory())
				.eq(WmsQualityStatusOperationEntity::getWarehouseCode, scope.warehouse())
				.isNotNull(WmsQualityStatusOperationEntity::getSpecialReturnBindingJson);
	}

	/**
	 * 单个被证明原件/root最多读取10000条完整证据，绝不以全仓无关历史限制合法新原件。
	 */
	private List<WmsQualityStatusOperationEntity> completeOperations(
			LambdaQueryWrapper<WmsQualityStatusOperationEntity> query, String subject) {
		var result = operations.selectList(query
				.orderByAsc(WmsQualityStatusOperationEntity::getSpecialReturnSequence).last("LIMIT 10001"));
		var sequences = new HashSet<Long>();
		for (var row : result) {
			require(row.getSpecialReturnSequence() != null && row.getSpecialReturnSequence() > 0 &&
					sequences.add(row.getSpecialReturnSequence()), "特殊接管缺少唯一永久办理序号，不能推断拆分先后");
		}
		require(result.size() <= 10000, subject + "超过完整读取上限，请联系管理员核对该原件");
		return result;
	}

	/**
	 * 从永久成功REHOLD派生首次撤销关系，不回写原处置状态。
	 * 在与处置相同的原仓事务锁中读取，旧指令一经撤销永久失效，也供QMS恢复断点及拒绝相反PASS。
	 */
	public String supersededByOperationId(WmsQualityStatusOperationEntity operation) {
		if (operation.getSpecialReturnBindingJson() == null || "HOLD".equals(operation.getAction())) {
			return null;
		}
		var binding = decode(operation.getSpecialReturnBindingJson());
		require(binding.rootHoldOperationId() != null, "特殊原处置缺少首次接管root");
		var scope = scope(binding.target().warehouseCode(), true);
		validateTarget(scope, binding.target());
		require(Objects.equals(operation.getTenantId(), scope.tenant()) &&
				Objects.equals(operation.getFactoryCode(), scope.factory()), "原处置不属于当前实际组织");
		// 不在已持有仓锁后再获取另一处置的request锁，避免与该原号回放产生锁顺序反转。
		repository.lockWarehouse(scope);
		var completed = operations.selectList(operationScope(scope)
				.eq(WmsQualityStatusOperationEntity::getAction, "HOLD")
				.eq(WmsQualityStatusOperationEntity::getStatus, "SUCCESS")
				.apply("special_return_binding_json::jsonb ->> 'reholdOperationId' = {0}", operation.getId().toString())
				.orderByAsc(WmsQualityStatusOperationEntity::getSpecialReturnSequence).last("LIMIT 1"));
		if (completed.isEmpty()) {
			return null;
		}
		var replacement = completed.get(0);
		var replacedBinding = decode(replacement.getSpecialReturnBindingJson());
		assertCase(operation, replacement);
		require(Objects.equals(binding.rootHoldOperationId(), replacedBinding.rootHoldOperationId()) &&
						binding.target().equals(replacedBinding.target()) &&
						replacement.getSpecialReturnSequence() != null && operation.getSpecialReturnSequence() != null &&
						replacement.getSpecialReturnSequence() > operation.getSpecialReturnSequence(),
				"原处置撤销关系缺少同root、原件及永久先后证明");
		return replacement.getId().toString();
	}

	/**
	 * 受权调用者的纯撤销证据读取，不要求今日写权、不取事务锁。
	 */
	public String readSupersededByOperationId(WmsQualityStatusOperationEntity operation) {
		if (operation.getSpecialReturnBindingJson() == null || "HOLD".equals(operation.getAction())) {
			return null;
		}
		var binding = decode(operation.getSpecialReturnBindingJson());
		require(binding.rootHoldOperationId() != null, "特殊原处置缺少首次接管root");
		var target = binding.target();
		require(Objects.equals(operation.getTenantId().toString(), target.tenantId()) &&
						Objects.equals(operation.getFactoryCode(), target.factoryCode()) &&
						Objects.equals(operation.getWarehouseCode(), target.warehouseCode()),
				"特殊原件组织及原仓不一致");
		var root = operations.selectOne(new LambdaQueryWrapper<WmsQualityStatusOperationEntity>()
				.eq(WmsQualityStatusOperationEntity::getTenantId, operation.getTenantId())
				.eq(WmsQualityStatusOperationEntity::getFactoryCode, operation.getFactoryCode())
				.eq(WmsQualityStatusOperationEntity::getId, id(binding.rootHoldOperationId())), false);
		require(root != null && "HOLD".equals(root.getAction()) && "SUCCESS".equals(root.getStatus()) &&
						root.getSpecialReturnBindingJson() != null && root.getSpecialReturnSequence() != null &&
						operation.getSpecialReturnSequence() != null &&
						root.getSpecialReturnSequence() < operation.getSpecialReturnSequence(),
				"特殊原处置缺少真实首次接管root及严格序号");
		var rootBinding = decode(root.getSpecialReturnBindingJson());
		require(rootBinding.rootHoldOperationId() == null && rootBinding.reholdOperationId() == null &&
				target.equals(rootBinding.target()), "特殊原处置与首次接管永久目标不一致");
		assertCase(root, operation);
		var completed = operations.selectList(new LambdaQueryWrapper<WmsQualityStatusOperationEntity>()
				.eq(WmsQualityStatusOperationEntity::getTenantId, operation.getTenantId())
				.eq(WmsQualityStatusOperationEntity::getFactoryCode, operation.getFactoryCode())
				.eq(WmsQualityStatusOperationEntity::getAction, "HOLD")
				.eq(WmsQualityStatusOperationEntity::getStatus, "SUCCESS")
				.apply("special_return_binding_json::jsonb ->> 'reholdOperationId' = {0}",
						operation.getId().toString())
				.orderByAsc(WmsQualityStatusOperationEntity::getSpecialReturnSequence).last("LIMIT 1"));
		if (completed.isEmpty()) {
			return null;
		}
		var replacement = completed.get(0);
		var replacedBinding = decode(replacement.getSpecialReturnBindingJson());
		assertCase(operation, replacement);
		require(Objects.equals(binding.rootHoldOperationId(), replacedBinding.rootHoldOperationId()) &&
						binding.target().equals(replacedBinding.target()) &&
						replacement.getSpecialReturnSequence() != null &&
						operation.getSpecialReturnSequence() != null &&
						replacement.getSpecialReturnSequence() > operation.getSpecialReturnSequence(),
				"原处置撤销关系缺少同root、原件及永久先后证明");
		return replacement.getId().toString();
	}

	private WmsQualityStatusOperationEntity operation(Scope scope, String operationId) {
		var row = operations.selectOne(new LambdaQueryWrapper<WmsQualityStatusOperationEntity>()
				.eq(WmsQualityStatusOperationEntity::getTenantId, scope.tenant())
				.eq(WmsQualityStatusOperationEntity::getFactoryCode, scope.factory())
				.eq(WmsQualityStatusOperationEntity::getWarehouseCode, scope.warehouse())
				.eq(WmsQualityStatusOperationEntity::getId, id(operationId)).last("FOR UPDATE"), false);
		require(row != null && row.getSpecialReturnBindingJson() != null &&
						row.getSpecialReturnSequence() != null && row.getSpecialReturnSequence() > 0,
				"原特殊质量操作不存在或无永久绑定/办理序号");
		return row;
	}

	private List<Map<String, String>> links(WmsQualityStatusOperationEntity operation) {
		require(Boolean.TRUE.equals(operation.getSplitRecorded()) && operation.getSplitRecordedAt() != null &&
				operation.getSplitLineageJson() != null, "原质量操作缺少永久拆分证明");
		try {
			List<Map<String, String>> result = json.readValue(operation.getSplitLineageJson(), new TypeReference<>() {
			});
			var seen = new LinkedHashSet<String>();
			for (var link : result) {
				id(link.get("parentStockId"));
				id(link.get("remainderStockId"));
				quantity(link.get("remainderQuantity"));
				require(!Objects.equals(link.get("parentStockId"), link.get("remainderStockId")) &&
						seen.add(link.get("remainderStockId")), "永久拆分库存身份重复");
			}
			require(seen.equals(ids(operation.getSplitRemainderStockIds())), "拆分余量ID与永久映射不一致");
			return result;
		} catch (com.kitex.common.core.exception.CheckedException e) {
			throw e;
		} catch (Exception e) {
			throw new com.kitex.common.core.exception.CheckedException("原质量操作拆分映射无法完整读取");
		}
	}

	private Set<String> ids(String value) {
		var result = new LinkedHashSet<String>();
		if (value != null && !value.isBlank()) {
			for (String item : value.split(",")) {
				id(item);
				require(result.add(item), "永久质量操作存在重复库存ID");
			}
		}
		return result;
	}

	private WmsLiveStockEntity stock(Scope scope, String stockId, boolean lock) {
		var query = new LambdaQueryWrapper<WmsLiveStockEntity>()
				.eq(WmsLiveStockEntity::getTenantId, scope.tenant())
				.eq(WmsLiveStockEntity::getFactoryCode, scope.factory())
				.eq(WmsLiveStockEntity::getWarehouseCode, scope.warehouse())
				.eq(WmsLiveStockEntity::getId, id(stockId));
		if (lock) {
			query.last("FOR UPDATE");
		}
		return stocks.selectOne(query, false);
	}

	private boolean isolated(WmsLiveStockEntity stock, String orderId) {
		return Integer.valueOf(1).equals(stock.getStatus()) && "ISOLATED".equals(stock.getQualityStatus()) &&
				("SPECIAL-RETURN:" + orderId).equals(stock.getQualityHoldNo()) &&
				"WMS_SPECIAL_RETURN".equals(stock.getQualitySourceType()) &&
				Objects.equals(stock.getQualitySourceId(), orderId);
	}

	private void assertPhysical(Map<String, String> frozen, Map<String, String> actual) {
		require(frozen != null && actual != null && actual.entrySet().stream()
						.filter(e -> !MUTABLE_QUALITY.contains(e.getKey()))
						.allMatch(e -> Objects.equals(frozen.get(e.getKey()), e.getValue())),
				"实物货权、首次来源、单件属性或库龄已漂移");
	}

	/**
	 * 生产与销售均以正式活动预留账为准，隔离接管不能挪用任一业务已承诺数量。
	 */
	private BigDecimal reserved(Long stockId) {
		return reservations.activeReservedNum(stockId).add(
				salesReservations.activeReservedNums(Set.of(stockId)).getOrDefault(stockId, BigDecimal.ZERO));
	}

	private BigDecimal amount(WmsLiveStockEntity stock) {
		require(stock.getMatNum() != null && Double.isFinite(stock.getMatNum()) && stock.getMatNum() > 0,
				"原库存数量无效");
		return BigDecimal.valueOf(stock.getMatNum());
	}

	/**
	 * 保存完整命令绑定原文；普通来源以NULL保持原语义。
	 */
	public String encode(Binding binding) {
		return binding == null ? null : repository.encode(binding);
	}

	/**
	 * 从永久JSON恢复原绑定，不按今日库存重建。
	 */
	public Binding decode(String value) {
		return value == null ? null : repository.decode(value, Binding.class);
	}

	/**
	 * 实物执行继续核当前精确原件身份及原仓位置，禁止凭相同QH执行已漂移的库存。
	 */
	public void validateExecution(WmsQualityStatusOperationEntity operation, List<WmsLiveStockEntity> actual) {
		if (operation.getSpecialReturnBindingJson() == null) {
			return;
		}
		var binding = decode(operation.getSpecialReturnBindingJson());
		var target = binding.target();
		require(binding.rootHoldOperationId() != null && binding.reholdOperationId() == null,
				"实物执行必须属于本root正式处置");
		var scope = scope(target.warehouseCode(), true);
		var root = operation(scope, binding.rootHoldOperationId());
		assertCase(operation, root);
		require(target.equals(decode(root.getSpecialReturnBindingJson()).target()), "实物执行原件与首次HOLD不一致");
		for (var stock : actual) {
			require(Objects.equals(stock.getTenantId(), scope.tenant()) &&
							Objects.equals(stock.getFactoryCode(), scope.factory()) &&
							Objects.equals(stock.getWarehouseCode(), scope.warehouse()) &&
							Objects.equals(text(stock.getSiteCode()), text(target.siteCode())) &&
							Objects.equals(text(stock.getContainerCode()), text(target.containerCode())),
					"实物执行库存组织或原仓位置已漂移");
			require(reserved(stock.getId()).signum() == 0, "实物执行仍存在生产或销售活动预留");
			assertPhysical(target.identity(), WmsSpecialStockProof.identity(stock));
		}
	}

	/**
	 * 永久初次命令保留空root；回执只投影真实root，不改写原命令。
	 */
	public Binding resultBinding(WmsQualityStatusOperationEntity operation) {
		var binding = decode(operation.getSpecialReturnBindingJson());
		return binding == null ? null : new Binding(binding.target(),
				binding.rootHoldOperationId() == null ? operation.getId().toString() : binding.rootHoldOperationId(),
				binding.reholdOperationId());
	}
}
