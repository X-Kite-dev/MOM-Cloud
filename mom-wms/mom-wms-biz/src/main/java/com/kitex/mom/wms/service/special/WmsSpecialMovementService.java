package com.kitex.mom.wms.service.special;

import com.kitex.mom.wms.dto.WmsSpecialMovementDTO.*;
import com.kitex.mom.wms.service.special.WmsSpecialAccess.Scope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.BigDecimal;
import java.util.*;

import static com.kitex.mom.wms.service.special.WmsSpecialRules.*;

/**
 * 备件、免费和外协的原申请、异人审核、实际收发、逆向与结案编排。
 * 详情在原仓事务内核验永久凭据，并附实际登记时间；读模型不改写原命令和库存事实。
 */
@Service
@RequiredArgsConstructor
public class WmsSpecialMovementService {
	private final WmsSpecialRepository repository;
	private final WmsSpecialAccess access;
	private final WmsSpecialPolicyService policies;
	private final WmsSpecialStockPosting posting;
	private final WmsSpecialMaterialService materials;
	private static final List<String> PERMISSIONS = List.of("view", "add", "review", "execute", "return",
			"close",
			"config");

	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Context context(String warehouse) {
		var scope = access.scope(warehouse, false);
		repository.lockWarehouse(scope);
		var warehouseState = access.snapshot(scope);
		return new Context(1, scope.tenant().toString(), scope.factory(), scope.warehouse(), scope.operator(),
				access.mobileEnabled(warehouseState),
				PERMISSIONS.stream().filter(p -> access.allowed(scope, p)).toList(), policies.current(scope),
				Boolean.TRUE.equals(warehouseState.getStatus()), warehouseState.getWarehouseName());
	}

	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Page page(String warehouse, String kind, String state, String reference, int page, int size) {
		var scope = access.scope(warehouse, false);
		require(page > 0 && page <= 10000 && size > 0 && size <= 50, "分页参数无效");
		kind = text(kind);
		state = text(state);
		reference = text(reference);
		field(reference, 200, false, "来源查询条件");
		require(kind.isEmpty() || Set.of("SPARE", "FREE", "OUTSOURCE").contains(kind), "业务类型无效");
		require(state.isEmpty() ||
						Set.of("PENDING", "APPROVED", "REJECTED", "CANCELLED", "CLOSED").contains(state),
				"申请状态无效");
		repository.lockWarehouse(scope);
		var records = repository.list(scope, kind, state, reference, page, size).stream()
				.map(id -> view(scope, id))
				.toList();
		return new Page(records, page, size, repository.count(scope, kind, state, reference));
	}

	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public View read(String warehouse, String orderId) {
		var scope = access.scope(warehouse, false);
		repository.lockWarehouse(scope);
		return view(scope, orderId);
	}

	/**
	 * 在调用方原仓事务锁内核完整凭据，登记时间只用于只读履历，不参加业务数量计算。
	 *
	 * @param scope   已核权限的原实际组织与仓库
	 * @param orderId 当前原申请身份
	 * @return 原申请、守恒余额、完整凭据及逐请求对应的登记时间
	 */
	private View view(Scope scope, String orderId) {
		var document = actualOrder(scope, orderId);
		var registered = repository.registeredOperations(scope, orderId);
		var operations = registered.stream().map(WmsSpecialRepository.RegisteredOperation::receipt).toList();
		for (var receipt : operations) {
			validateReceiptScope(scope, receipt, false);
			require(Objects.equals(receipt.orderId(), orderId), "作业凭据不属于该申请");
			verifyPosting(scope, receipt);
		}
		var audit = registered.stream()
				.map(row -> new OperationAudit(row.receipt().requestId(), row.registeredAt())).toList();
		return new View(document, balance(document.plan(), flows(operations)), operations, audit);
	}

	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Receipt lookup(Command original) {
		envelope(original);
		var scope = access.scope(original.warehouseCode(), false);
		repository.lockRequest(scope, original.requestId());
		var receipt = repository.operation(scope, original.requestId());
		if (receipt != null) {
			original(scope, original, receipt);
			verifyPosting(scope, receipt);
		}
		return receipt;
	}

	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Receipt end(End input) {
		require(input != null, "请保留原请求后核对");
		var command = input.original();
		envelope(command);
		field(input.reason(), 500, true, "结束原因");
		var scope = access.scope(command.warehouseCode(), true);
		access.requireAction(scope, permission(command.action()));
		repository.lockRequest(scope, command.requestId());
		var receipt = repository.operation(scope, command.requestId());
		if (receipt != null) {
			original(scope, command, receipt);
			verifyPosting(scope, receipt);
			return receipt;
		}
		repository.lockWarehouse(scope);
		receipt = new Receipt(1, scope.tenant().toString(), scope.factory(), scope.warehouse(),
				scope.operator(),
				command.requestId(), "CANCELLED", command.action(), command, command.orderId(), null, null,
				null,
				List.of(), input.reason());
		repository.insertOperation(scope, command, receipt);
		return receipt;
	}

	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Receipt perform(Command command, boolean mobile) {
		envelope(command);
		var scope = access.scope(command.warehouseCode(), true);
		access.requireAction(scope, permission(command.action()));
		repository.lockRequest(scope, command.requestId());
		var recorded = repository.operation(scope, command.requestId());
		if (recorded != null) {
			original(scope, command, recorded);
			require("REGISTERED".equals(recorded.status()), "原请求已永久安全结束，不能再执行");
			verifyPosting(scope, recorded);
			return recorded;
		}
		if (mobile) {
			require(access.mobileEnabled(scope), "当前仓库未启用手机作业");
		}
		repository.lockWarehouse(scope);
		OrderDocument before;
		OrderDocument after;
		WmsSpecialStockPosting.Posted posted = null;
		if (command.action() == Action.CREATE) {
			require(command.orderId() == null && command.expectedVersion() == null && command.plan() != null &&
					command.lines().isEmpty() && command.closeLines().isEmpty(), "新申请内容不完整");
			var policy = policies.current(scope);
			var rule = rule(policy, command.plan().kind());
			checkPlan(command.plan(), rule);
			access.warehouse(scope);
			access.configured(scope);
			if (mobile) {
				require(access.mobileEnabled(scope), "当前仓库未启用手机作业");
			}
			var frozen = materials.freeze(scope, command.plan());
			checkPlan(frozen, rule);
			after = repository.insertOrder(scope, frozen, rule, policy.version());
			validateDocument(scope, after);
			require(after.plan().equals(frozen) && "PENDING".equals(after.state()) &&
							"1".equals(after.version()),
					"申请原件保存未确认");
		} else {
			require(command.plan() == null, "原申请不得在办理时改写计划");
			before = actualOrder(scope, command.orderId());
			require(Objects.equals(command.expectedVersion(), before.version()), "申请已变化，请刷新原单后核对");
			var operations = repository.operations(scope, before.id());
			for (var receipt : operations) {
				validateReceiptScope(scope, receipt, false);
				require(before.id().equals(receipt.orderId()), "原操作凭据归属不一致");
				verifyPosting(scope, receipt);
			}
			var previous = flows(operations);
			balance(before.plan(), previous);
			String state = before.state();
			String reviewer = before.reviewedBy();
			switch (command.action()) {
				case APPROVE, REJECT -> {
					require("PENDING".equals(state) && !scope.operator().equals(before.createdBy()),
							"申请须由另一名审核人员办理");
					require(command.lines().isEmpty() && command.closeLines().isEmpty(),
							"审核不能夹带库存操作");
					field(text(command.reason()), 500, true, "审核说明");
					if (command.action() == Action.APPROVE) {
						checkPlan(before.plan(), rule(policies.current(scope), before.plan().kind()));
						access.warehouse(scope);
						access.configured(scope);
					}
					state = command.action() == Action.APPROVE ? "APPROVED" : "REJECTED";
					reviewer = scope.operator();
				}
				case CANCEL -> {
					require(Set.of("PENDING", "APPROVED").contains(state) && previous.isEmpty(),
							"已发生实收实发的申请不能取消，请按原件退回或对账关闭");
					require(scope.operator().equals(before.createdBy()) || access.allowed(scope, "review"),
							"仅申请人或审核人员可取消");
					require(command.lines().isEmpty() && command.closeLines().isEmpty(),
							"取消不能夹带库存操作");
					field(text(command.reason()), 500, true, "取消原因");
					state = "CANCELLED";
				}
				case CLOSE -> {
					require("APPROVED".equals(state) && !scope.operator().equals(before.createdBy()),
							"请由另一名审核人员关闭已批准申请");
					require(command.lines().isEmpty(), "关闭不执行额外库存收发");
					field(text(command.reason()), 500, true, "关闭原因");
					field(text(command.evidenceReference()), 200, true, "对账凭证");
					if (before.plan().kind() == Kind.OUTSOURCE) {
						require(operations.stream().filter(r -> !r.flows().isEmpty())
										.noneMatch(r -> r.operatorCode().equals(scope.operator())),
								"外协关闭须由实物操作之外的人员核对");
					}
					checkClosure(before.plan(), previous, command.closeLines());
					state = "CLOSED";
				}
				case EXECUTE, RECEIVE_OUTPUT, RETURN -> {
					require("APPROVED".equals(state) ||
							command.action() == Action.RETURN && "CLOSED".equals(state) &&
									before.plan().kind() != Kind.OUTSOURCE, "当前申请状态不能继续该实物作业");
					require(command.closeLines().isEmpty(), "实物作业不能夹带耗用或短交处置");
					field(text(command.reason()), 500, true, "作业说明");
					var current = rule(policies.current(scope), before.plan().kind());
					if (command.action() == Action.RETURN) {
						require(current.allowReturns(), "当前仓库规则不允许原件退回");
						field(text(command.evidenceReference()), 200, true, "退回凭证");
					} else {
						require(current.enabled(), "该特殊收发业务当前已停用");
						access.configured(scope);
					}
					checkExecution(before.plan(), previous, command.action(), command.lines());
					posted = posting.post(scope, before, command, previous);
					checkPosted(command, posted);
				}
				default -> throw new IllegalStateException("未处理的特殊收发动作");
			}
			after = repository.advance(scope, before.id(), before.version(), state, reviewer);
			validateDocument(scope, after);
			require(Objects.equals(after.plan(), before.plan()) &&
							Objects.equals(after.rule(), before.rule()) &&
							Objects.equals(after.policyVersion(), before.policyVersion()) &&
							Objects.equals(after.createdBy(), before.createdBy()) &&
							Objects.equals(after.createdAt(), before.createdAt()) &&
							Objects.equals(after.state(), state) &&
							Objects.equals(after.reviewedBy(), reviewer) &&
							id(after.version()) == Math.addExact(id(before.version()), 1),
					"申请状态回读与本次办理不一致，整笔操作已回滚");
		}
		var receipt = new Receipt(1, scope.tenant().toString(), scope.factory(), scope.warehouse(),
				scope.operator(),
				command.requestId(), "REGISTERED", command.action(), command, after.id(), after.version(),
				posted == null ? null : posted.billId(), posted == null ? null : posted.billNo(),
				posted == null ? List.of() : List.copyOf(posted.flows()), null);
		verifyPosting(scope, receipt);
		repository.insertOperation(scope, command, receipt);
		return receipt;
	}

	private void checkPosted(Command command, WmsSpecialStockPosting.Posted posted) {
		require(posted != null && posted.billId() != null && posted.billNo() != null &&
						!posted.billNo().isBlank() &&
						posted.direction() != null && posted.flows() != null && !posted.flows().isEmpty(),
				"本次作业没有完整的实际单据和库存凭据");
		id(posted.billId());
		var expected = new HashMap<String, BigDecimal>();
		var actual = new HashMap<String, BigDecimal>();
		var ids = new HashSet<String>();
		for (var item : command.lines()) {
			expected.merge(key(item.lineId(), item.sourceFlowId()), quantity(item.quantity()), BigDecimal::add);
		}
		for (var flow : posted.flows()) {
			require(flow != null && flow.direction() == posted.direction() && flow.identity() != null &&
					!flow.identity().isEmpty() && ids.add(flow.id()), "实际库存凭据重复、方向或身份不完整");
			id(flow.id());
			id(flow.stockId());
			id(flow.billDetailId());
			actual.merge(key(flow.lineId(), flow.sourceFlowId()), quantity(flow.quantity()), BigDecimal::add);
		}
		require(expected.keySet().equals(actual.keySet()) &&
						expected.entrySet().stream().allMatch(e -> e.getValue().compareTo(actual.get(e.getKey())) == 0),
				"实际记账凭据与本次请求数量不一致，整笔操作已回滚");
	}

	private String key(String line, String source) {
		return line + ":" + text(source);
	}

	private List<Flow> flows(List<Receipt> operations) {
		return operations.stream().filter(r -> "REGISTERED".equals(r.status())).flatMap(r -> r.flows().stream())
				.toList();
	}

	private Rule rule(PolicySnapshot value, Kind kind) {
		require(value != null && value.rules() != null, "仓库规则未完整读取");
		return value.rules().stream().filter(r -> r.kind() == kind).findFirst()
				.orElseThrow(
						() -> new com.kitex.common.core.exception.CheckedException("未找到该特殊收发规则"));
	}

	private OrderDocument actualOrder(Scope scope, String orderId) {
		var value = repository.order(scope, orderId);
		validateDocument(scope, value);
		return value;
	}

	private void validateDocument(Scope scope, OrderDocument value) {
		require(value != null && Objects.equals(value.tenantId(), scope.tenant().toString()) &&
				Objects.equals(value.factoryCode(), scope.factory()) &&
				Objects.equals(value.warehouseCode(), scope.warehouse()), "未找到当前仓库的实际申请");
		id(value.id());
		id(value.version());
		id(value.policyVersion());
		checkPlan(value.plan(), value.rule());
	}

	private void original(Scope scope, Command command, Receipt receipt) {
		validateReceiptScope(scope, receipt, true);
		require(Objects.equals(command, receipt.original()) && receipt.action() == command.action() &&
				receipt.requestId().equals(command.requestId()), "原请求号已被其它内容使用，请保留原号核对");
	}

	private void validateReceiptScope(Scope scope, Receipt receipt, boolean own) {
		require(receipt != null && receipt.contractVersion() == 1 &&
						Objects.equals(receipt.tenantId(), scope.tenant().toString()) &&
						Objects.equals(receipt.factoryCode(), scope.factory()) &&
						Objects.equals(receipt.warehouseCode(), scope.warehouse()) &&
						(!own || Objects.equals(receipt.operatorCode(), scope.operator())) &&
						receipt.original() != null &&
						receipt.action() == receipt.original().action() &&
						Objects.equals(receipt.requestId(), receipt.original().requestId()) &&
						Objects.equals(receipt.warehouseCode(), receipt.original().warehouseCode()) &&
						Set.of("REGISTERED", "CANCELLED").contains(receipt.status()) && receipt.flows() != null,
				"原办理凭据身份、组织或内容不一致");
	}

	private void verifyPosting(Scope scope, Receipt receipt) {
		boolean physical = "REGISTERED".equals(receipt.status()) &&
				Set.of(Action.EXECUTE, Action.RECEIVE_OUTPUT, Action.RETURN).contains(receipt.action());
		require(physical ?
				receipt.billId() != null && !receipt.flows().isEmpty() :
				receipt.billId() == null && receipt.flows().isEmpty(), "原办理凭据缺少对应实际库存事实");
		if (physical) {
			posting.verify(scope, receipt);
		}
	}

	private String permission(Action action) {
		return switch (action) {
			case CREATE, CANCEL -> "add";
			case APPROVE, REJECT -> "review";
			case EXECUTE, RECEIVE_OUTPUT -> "execute";
			case RETURN -> "return";
			case CLOSE -> "close";
		};
	}

	private void envelope(Command command) {
		require(command != null && command.action() != null, "请保留完整原请求");
		require(command.requestId() != null && command.requestId().matches("[a-zA-Z0-9][a-zA-Z0-9._:-]{7,99}"),
				"原请求号无效");
		field(command.warehouseCode(), 100, true, "实际仓库");
		require(command.lines() != null && command.lines().size() <= 200 && command.closeLines() != null &&
				command.closeLines().size() <= 10200, "请保留完整原请求明细");
		if (command.action() != Action.CREATE) {
			id(command.orderId());
			id(command.expectedVersion());
		}
	}
}
