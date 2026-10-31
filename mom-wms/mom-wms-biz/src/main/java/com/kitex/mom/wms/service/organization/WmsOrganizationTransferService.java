package com.kitex.mom.wms.service.organization;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsOrganizationTransferDTO.*;
import com.kitex.mom.wms.dto.app.WmsMobileOrganizationTransferDTO.MobileContext;
import com.kitex.mom.wms.entity.organization.*;
import com.kitex.mom.wms.repository.WmsOrganizationTransferRepository;
import com.kitex.mom.wms.service.organization.WmsOrganizationTransferAccess.Scope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.kitex.mom.wms.service.organization.WmsOrganizationTransferRules.*;
import static com.kitex.mom.wms.service.organization.WmsOrganizationTransferCommand.text;

/**
 * 实物与本侧台账同事务提交；另一端只能通过已授权协同单交接，不取得本侧库存通用访问权。
 */
@Service
@RequiredArgsConstructor
public class WmsOrganizationTransferService {
	private final WmsOrganizationTransferRepository repository;
	private final WmsOrganizationTransferAccess access;
	private final WmsOrganizationTransferPolicyService policies;
	private final WmsOrganizationTransferStockService stock;
	private final WmsOrganizationTransferReceiptCodec json;

	public Context context(String warehouse) {
		var scope = access.scope(warehouse, false);
		return context(scope);
	}

	private Context context(Scope scope) {
		var actions = Arrays.stream(Action.values()).filter(action -> permitted(scope, action)).toList();
		return new Context(1, scope.tenant(), scope.factory(), scope.operator(), scope.warehouse(), actions,
				access.allowed(scope, "config"), scope.userId());
	}

	public MobileContext mobileContext(String warehouse) {
		var scope = access.scope(warehouse, false);
		var actual = context(scope);
		var mobile = mobileState(scope);
		return new MobileContext(actual.contractVersion(), actual.tenantId(), actual.factoryCode(),
				actual.operatorCode(), actual.warehouseCode(), mobile.enabled() ? actual.permissions() : List.of(),
				mobile.enabled() && actual.canConfigure(), actual.operatorId(), 1, mobile.enabled(), mobile.message());
	}

	private record MobileState(boolean enabled, String message) {
	}

	private MobileState mobileState(Scope scope) {
		try {
			boolean enabled = access.mobileEnabled(scope);
			return new MobileState(enabled, enabled ? "" : "该仓库未开启手机端作业");
		} catch (RuntimeException unavailable) {
			// 身份和查看权限已在探测外核实；配置故障只禁用新能力，保留原号恢复。
			return new MobileState(false, "仓库手机端配置暂不可用，可继续核对原请求");
		}
	}

	public List<WmsOrganizationTransferPolicy> directory(String warehouse) {
		var scope = access.scope(warehouse, false);
		access.action(scope, Action.CREATE);
		policies.active(scope);
		var rows = repository.directory(scope.tenant(), scope.factory());
		require(rows.size() <= 1000, "合作仓库超过1000项，请由管理员分组维护库存组织");
		// 目录仅发布合作组织/仓库元数据，不公开配置维护人或对方库存。
		for (var row : rows) {
			row.setUpdatedBy(null);
			row.setUpdatedTime(null);
		}
		return rows;
	}

	public Page page(String warehouse, String state, String billNo, int page, int size) {
		var scope = access.scope(warehouse, false);
		require(page > 0 && page <= 10000 && size > 0 && size <= 100, "分页参数无效");
		return repository.page(scope, text(state), text(billNo), (page - 1) * size, size);
	}

	public Detail detail(String warehouse, Long id) {
		var scope = access.scope(warehouse, false);
		return detail(scope, id);
	}

	public Detail mobileDetail(String warehouse, Long id) {
		var scope = access.scope(warehouse, false);
		var actual = detail(scope, id);
		if (mobileState(scope).enabled()) {
			return actual;
		}
		return new Detail(actual.bill(), actual.lines(), actual.effects(), actual.history(), List.of(),
				actual.effectsTruncated(), actual.historyTruncated());
	}

	private Detail detail(Scope scope, Long id) {
		var bill = own(scope, id, false);
		var lines = repository.lines(scope.tenant(), id);
		var effects = repository.effects(scope.tenant(), id, 0, 101);
		var history = repository.history(scope.tenant(), id, 0, 101);
		return new Detail(bill, lines.stream().map(this::lineView).toList(), effects.stream().limit(100).toList(),
				history.stream().limit(100).map(this::historyView).toList(), actions(scope, bill), effects.size() > 100,
				history.size() > 100);
	}

	public List<History> history(String warehouse, Long id, int page) {
		var scope = access.scope(warehouse, false);
		own(scope, id, false);
		require(page > 0 && page <= 10000, "履历分页无效");
		return repository.history(scope.tenant(), id, (page - 1) * 100, 100).stream().map(this::historyView).toList();
	}

	public List<Effect> effects(String warehouse, Long id, int page) {
		var scope = access.scope(warehouse, false);
		own(scope, id, false);
		require(page > 0 && page <= 10000, "实物分页无效");
		return repository.effects(scope.tenant(), id, (page - 1) * 100, 100);
	}

	public Receipt lookup(String warehouse, String request) {
		var scope = access.scope(warehouse, false);
		require(request != null && request.matches("[A-Za-z0-9][A-Za-z0-9_-]{15,63}"), "原请求号无效");
		var original = repository.operation(scope.tenant(), request);
		if (original != null) return replay(scope, original, null);
		return new Receipt(1, scope.tenant(), scope.factory(), scope.operator(), scope.warehouse(), request, null,
				"NOT_FOUND", null, "", "", null, null, List.of(), null, "", scope.userId());
	}

	@Transactional(rollbackFor = Exception.class)
	public Receipt execute(Command command) {
		return execute(command, false);
	}

	@Transactional(rollbackFor = Exception.class)
	public Receipt executeMobile(Command command) {
		return execute(command, true);
	}

	private Receipt execute(Command command, boolean mobile) {
		String payload = WmsOrganizationTransferCommand.payload(command);
		var scope = access.scope(command.getWarehouseCode(), true);
		checkIntent(scope, command);
		access.action(scope, command.getAction());
		repository.lock(scope.tenant(), "REQUEST:" + command.getRequestId());
		var original = repository.operation(scope.tenant(), command.getRequestId());
		if (original != null) return replay(scope, original, payload);
		if (mobile) {
			access.checkMobile(scope);
		}
		WmsOrganizationTransferBill bill;
		List<Effect> effects = new ArrayList<>();
		if (command.getAction() == Action.CREATE) bill = create(scope, command);
		else {
			bill = own(scope, command.getBillId(), true);
			require(Objects.equals(bill.getVersion(), command.getExpectedVersion()),
					"单据已由其他操作推进，请核对原请求后重新读取");
			int previous = bill.getVersion();
			boolean source = isSource(scope, bill);
			String state = next(bill.getState(), command.getAction(), source);
			var lines = repository.lines(scope.tenant(), bill.getId());
			require(!lines.isEmpty() && lines.size() <= 500, "调拨明细缺失或超过上限");
			switch (command.getAction()) {
				case CONFIRM -> {
					activeBoth(scope, bill);
					companyAgreement(bill.getSourceCompanyCode(), bill.getTargetCompanyCode(),
							cross(bill.getSourcePolicyId(), scope), cross(bill.getTargetPolicyId(), scope),
							bill.getAgreementRef(), bill.getCreatedById().toString(), scope.userId().toString());
					for (var line : lines) stock.material(scope, stock.source(line));
					bill.setConfirmedBy(scope.operator());
					bill.setConfirmedById(scope.userId());
				}
				case SHIP -> {
					activeBoth(scope, bill);
					require(bill.getConfirmedBy() != null, "接收方尚未确认");
					evidence(command);
					Map<Long, InputLine> shipping = new HashMap<>();
					command.getLines().forEach(input -> shipping.put(input.getLineId(), input));
					require(shipping.size() == lines.size(), "发出须完整核对已确认明细");
					for (var line : lines) {
						require(line.getIssueFlowId() == null && shipping.containsKey(line.getId()),
								"发出明细不属于原单或已经发出");
						effects.add(stock.ship(scope, bill, line, shipping.get(line.getId()), command.getRequestId()));
						saveLine(line);
					}
				}
				case RECEIVE, RETURN -> {
					evidence(command);
					access.warehouse(scope);
					Map<Long, WmsOrganizationTransferLine> byId = new HashMap<>();
					lines.forEach(line -> byId.put(line.getId(), line));
					for (var input : command.getLines()) {
						var line = byId.get(input.getLineId());
						require(line != null, "实物行不属于当前调拨单");
						require(input.getQuantity().compareTo(remaining(line)) <= 0, "本次数量超过该行未结数量");
						boolean returning = command.getAction() == Action.RETURN;
						effects.add(stock.receive(scope, bill, line, input, command.getRequestId(), returning));
						if (returning) line.setReturnedQuantity(line.getReturnedQuantity().add(input.getQuantity()));
						else line.setReceivedQuantity(line.getReceivedQuantity().add(input.getQuantity()));
						saveLine(line);
					}
					BigDecimal remaining = lines.stream().map(this::remaining).reduce(BigDecimal.ZERO, BigDecimal::add);
					state = command.getAction() == Action.RETURN ? returnState(remaining,
							lines.stream().map(WmsOrganizationTransferLine::getReceivedQuantity).reduce(BigDecimal.ZERO,
									BigDecimal::add)) : receiptState(remaining);
				}
				case CANCEL, REQUEST_RETURN -> reason(command);
				case PROPOSE_EXCEPTION -> {
					reason(command);
					evidence(command);
					require(lines.stream().anyMatch(line -> remaining(line).signum() > 0), "没有可申报的未结数量");
					bill.setPreviousState(bill.getState());
					bill.setExceptionReason(text(command.getReason()));
					bill.setExceptionEvidence(text(command.getEvidence()));
				}
				case APPROVE_EXCEPTION -> {
					reason(command);
					evidence(command);
					var prior = repository.history(scope.tenant(), bill.getId(), 0, 1);
					require(!prior.isEmpty() && !prior.get(0).operatorId().equals(scope.userId()),
							"差异申报与确认须由不同操作人完成");
					for (var line : lines) {
						line.setExceptionQuantity(line.getExceptionQuantity().add(remaining(line)));
						saveLine(line);
					}
					state = "CLOSED_WITH_DIFFERENCE";
				}
				case REJECT_EXCEPTION -> {
					reason(command);
					require(bill.getPreviousState() != null, "差异申请前状态缺失");
					state = bill.getPreviousState();
				}
				default -> throw new CheckedException("当前动作无效");
			}
			bill.setState(state);
			bill.setVersion(previous + 1);
			bill.setUpdatedTime(now());
			require(repository.updateBill(bill, previous) == 1, "组织调拨状态保存失败");
		}
		var receipt = new Receipt(1, scope.tenant(), scope.factory(), scope.operator(), scope.warehouse(),
				command.getRequestId(), command.getAction(),
				"APPLIED", bill.getId(), bill.getBillNo(), bill.getState(), bill.getVersion(), payload,
				List.copyOf(effects), now(), "", scope.userId());
		saveReceipt(scope, command, payload, receipt);
		for (var effect : effects)
			require(repository.insertEffect(scope.tenant(), bill.getId(), command.getRequestId(), effect,
					IdUtil.getSnowflakeNextId()) == 1, "组织调拨实物关联保存失败");
		return receipt;
	}

	@Transactional(rollbackFor = Exception.class)
	public Receipt abandon(Command command, String reason) {
		String payload = WmsOrganizationTransferCommand.payload(command);
		var scope = access.scope(command.getWarehouseCode(), true);
		checkIntent(scope, command);
		access.action(scope, command.getAction());
		require(!text(reason).isEmpty() && text(reason).length() <= 500, "请填写500字以内的取消原因");
		repository.lock(scope.tenant(), "REQUEST:" + command.getRequestId());
		var original = repository.operation(scope.tenant(), command.getRequestId());
		if (original != null) return replay(scope, original, payload);
		var bill = command.getBillId() == null ? null : own(scope, command.getBillId(), true);
		var receipt = new Receipt(1, scope.tenant(), scope.factory(), scope.operator(), scope.warehouse(),
				command.getRequestId(), command.getAction(),
				"ABANDONED", command.getBillId(), bill == null ? "" : bill.getBillNo(),
				bill == null ? "" : bill.getState(), bill == null ? null : bill.getVersion(), payload, List.of(), now(),
				text(reason), scope.userId());
		saveReceipt(scope, command, payload, receipt);
		return receipt;
	}

	private WmsOrganizationTransferBill create(Scope scope, Command command) {
		var source = policies.active(scope);
		var target = repository.policy(scope.tenant(), command.getTargetPolicyId());
		require(target != null && Boolean.TRUE.equals(target.getEnabled()) &&
				!target.getFactoryCode().equals(scope.factory()), "请选择当前租户已发布的其他工厂合作仓库");
		if (!source.getCompanyCode().equals(target.getCompanyCode())) {
			require(Boolean.TRUE.equals(source.getCrossCompany()) && Boolean.TRUE.equals(target.getCrossCompany()) &&
					!text(command.getAgreementRef()).isEmpty(), "跨公司须双方启用并填写业务依据");
		}
		var bill = new WmsOrganizationTransferBill();
		bill.setId(IdUtil.getSnowflakeNextId());
		bill.setTenantId(scope.tenant());
		bill.setBillNo("OT-" + bill.getId());
		bill.setSourcePolicyId(source.getId());
		bill.setSourceFactoryCode(scope.factory());
		bill.setSourceWarehouseCode(scope.warehouse());
		bill.setSourceWarehouseName(source.getWarehouseName());
		bill.setSourceCompanyCode(source.getCompanyCode());
		bill.setSourceCompanyName(source.getCompanyName());
		bill.setTargetPolicyId(target.getId());
		bill.setTargetFactoryCode(target.getFactoryCode());
		bill.setTargetWarehouseCode(target.getWarehouseCode());
		bill.setTargetWarehouseName(target.getWarehouseName());
		bill.setTargetCompanyCode(target.getCompanyCode());
		bill.setTargetCompanyName(target.getCompanyName());
		bill.setState("PENDING_CONFIRMATION");
		bill.setVersion(1);
		bill.setCreatedBy(scope.operator());
		bill.setCreatedById(scope.userId());
		bill.setAgreementRef(text(command.getAgreementRef()));
		bill.setRemark(text(command.getReason()));
		bill.setCreatedTime(now());
		bill.setUpdatedTime(now());
		require(repository.insertBill(bill) == 1, "组织调拨申请保存失败");
		for (var input : command.getLines()) {
			var sourceStock = stock.freeze(scope, input.getStockId(), input.getQuantity(), source.getCompanyCode());
			var line = new WmsOrganizationTransferLine();
			line.setId(IdUtil.getSnowflakeNextId());
			line.setTenantId(scope.tenant());
			line.setBillId(bill.getId());
			line.setSourceStockId(input.getStockId());
			line.setQuantity(input.getQuantity());
			line.setReceivedQuantity(BigDecimal.ZERO);
			line.setReturnedQuantity(BigDecimal.ZERO);
			line.setExceptionQuantity(BigDecimal.ZERO);
			line.setSourceSnapshot(JSONUtil.toJsonStr(sourceStock));
			require(repository.insertLine(line) == 1, "组织调拨申请明细保存失败");
		}
		return bill;
	}

	private void activeBoth(Scope scope, WmsOrganizationTransferBill bill) {
		repository.lockPolicies(scope.tenant(), bill.getSourcePolicyId(), bill.getTargetPolicyId());
		policies.active(scope);
		var source = repository.policy(scope.tenant(), bill.getSourcePolicyId());
		var target = repository.policy(scope.tenant(), bill.getTargetPolicyId());
		require(source != null && target != null && Boolean.TRUE.equals(source.getEnabled()) &&
				Boolean.TRUE.equals(target.getEnabled())
				&& Objects.equals(source.getCompanyCode(), bill.getSourceCompanyCode()) &&
				Objects.equals(target.getCompanyCode(), bill.getTargetCompanyCode()), "双方组织策略已停用或归属不一致");
		if (!bill.getSourceCompanyCode().equals(bill.getTargetCompanyCode()))
			require(Boolean.TRUE.equals(source.getCrossCompany()) && Boolean.TRUE.equals(target.getCrossCompany()),
					"双方跨公司策略已关闭");
	}

	private boolean cross(Long policy, Scope scope) {
		return Boolean.TRUE.equals(repository.policy(scope.tenant(), policy).getCrossCompany());
	}

	private WmsOrganizationTransferBill own(Scope scope, Long id, boolean lock) {
		require(id != null && id > 0, "请选择组织调拨单");
		var bill = repository.bill(scope, id, lock);
		require(bill != null, "调拨单不属于当前实际工厂和仓库");
		return bill;
	}

	private boolean isSource(Scope scope, WmsOrganizationTransferBill bill) {
		return scope.factory().equals(bill.getSourceFactoryCode()) &&
				scope.warehouse().equals(bill.getSourceWarehouseCode());
	}

	private List<Action> actions(Scope scope, WmsOrganizationTransferBill bill) {
		return Arrays.stream(Action.values()).filter(
				action -> action != Action.CREATE && permitted(scope, action)).filter(action -> {
			try {
				next(bill.getState(), action, isSource(scope, bill));
				return true;
			} catch (CheckedException invalid) {
				return false;
			}
		}).toList();
	}

	private boolean permitted(Scope scope, Action action) {
		try {
			access.action(scope, action);
			return true;
		} catch (CheckedException denied) {
			return false;
		}
	}

	private Receipt replay(Scope scope, Operation original, String payload) {
		require(Objects.equals(original.factoryCode(), scope.factory()) &&
				Objects.equals(original.warehouseCode(), scope.warehouse()) &&
				Objects.equals(original.operatorCode(), scope.operator())
				&& Objects.equals(original.operatorId(), scope.userId()), "原操作不属于当前组织、仓库或操作人");
		access.action(scope, original.action());
		require(payload == null || payload.equals(original.commandPayload()), "原操作号已被不同内容使用");
		var receipt = read(original.resultPayload(), Receipt.class);
		require(Objects.equals(receipt.tenantId(), scope.tenant()) &&
				Objects.equals(receipt.requestId(), original.requestId())
				&& Objects.equals(receipt.factoryCode(), scope.factory()) &&
				Objects.equals(receipt.operatorCode(), scope.operator())
				&& Objects.equals(receipt.operatorId(), scope.userId())
				&& Objects.equals(receipt.warehouseCode(), scope.warehouse()) &&
				Objects.equals(receipt.action(), original.action())
				&& Objects.equals(receipt.status(), original.status()) &&
				Objects.equals(receipt.requestPayload(), original.commandPayload()), "原操作回执不完整");
		return receipt;
	}

	private void checkIntent(Scope scope, Command command) {
		require(Objects.equals(scope.tenant(), command.getTenantId()) &&
						Objects.equals(scope.userId(), command.getOperatorId()) &&
						Objects.equals(scope.factory(), command.getFactoryCode()),
				"办理组织或登录身份已变化，请核对原请求后重新准备");
	}

	private void saveReceipt(Scope scope, Command command, String payload, Receipt receipt) {
		require(repository.insertOperation(
				new Operation(scope.tenant(), command.getRequestId(), scope.factory(), scope.warehouse(),
						scope.operator(), command.getAction(), receipt.billId(), payload, write(receipt),
						receipt.status(), receipt.completedTime(), scope.userId())) == 1, "组织调拨永久回执保存失败");
	}

	private LineView lineView(WmsOrganizationTransferLine line) {
		var source = stock.source(line);
		return new LineView(line.getId(), line.getSourceStockId(), source.getMatCode(), source.getMatName(),
				source.getMatBatch(), source.getMatUnit(), source.getMatBarcode(),
				Boolean.TRUE.equals(source.getSerialManaged()), source.getQualityStatus(), source.getQualityResult(),
				line.getQuantity(), line.getReceivedQuantity(), line.getReturnedQuantity(), line.getExceptionQuantity(),
				remaining(line));
	}

	private History historyView(Operation operation) {
		var receipt = read(operation.resultPayload(), Receipt.class);
		var command = JSONUtil.parseObj(operation.commandPayload());
		return new History(operation.requestId(), operation.action(), operation.factoryCode(), operation.operatorCode(),
				receipt.state(), command.getStr("reason"), command.getStr("evidence"), operation.completedTime());
	}

	private BigDecimal remaining(WmsOrganizationTransferLine line) {
		return WmsOrganizationTransferRules.remaining(line.getQuantity(), line.getReceivedQuantity(),
				line.getReturnedQuantity(), line.getExceptionQuantity());
	}

	private void saveLine(WmsOrganizationTransferLine line) {
		remaining(line);
		require(repository.updateLine(line) == 1, "组织调拨行数量保存失败");
	}

	private void reason(Command command) {
		require(!text(command.getReason()).isEmpty(), "请填写原因");
	}

	private void evidence(Command command) {
		require(!text(command.getEvidence()).isEmpty(), "请填写实物交接或验收凭证");
	}

	private LocalDateTime now() {
		return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
	}

	private String write(Object value) {
		try {
			return json.writeValueAsString(value);
		} catch (Exception invalid) {
			throw new CheckedException("组织调拨回执无法保存");
		}
	}

	private <T> T read(String value, Class<T> type) {
		try {
			return json.readValue(value, type);
		} catch (Exception invalid) {
			throw new CheckedException("组织调拨历史回执无法读取");
		}
	}
}
