package com.kitex.mom.wms.service.special;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsSpecialMovementDTO.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Stream;

/**
 * 只计算申请额度和原件数量；实际库存由既有收发引擎处理。
 */
public final class WmsSpecialRules {
	private WmsSpecialRules() {
	}

	public static void require(boolean value, String message) {
		if (!value) {
			throw new CheckedException(message);
		}
	}

	public static long id(String value) {
		require(value != null && value.matches("[1-9][0-9]{0,18}") &&
						(value.length() < 19 || value.compareTo("9223372036854775807") <= 0),
				"业务标识无效，请从实际单据选择");
		return Long.parseLong(value);
	}

	public static BigDecimal quantity(String value) {
		require(value != null && value.matches("(?:0|[1-9][0-9]{0,14})(?:\\.[0-9]{1,6})?"),
				"数量须为普通十进制正数，最多六位小数");
		var number = new BigDecimal(value);
		require(number.signum() > 0 && Double.isFinite(number.doubleValue()) &&
						BigDecimal.valueOf(number.doubleValue()).compareTo(number) == 0,
				"数量超出现有库存引擎的精确计量范围，请拆分办理");
		return number;
	}

	public static String number(BigDecimal value) {
		return value.stripTrailingZeros().toPlainString();
	}

	public static String text(String value) {
		return value == null ? "" : value;
	}

	public static void field(String value, int max, boolean mandatory, String label) {
		require(value != null && value.length() <= max && value.equals(value.trim()) &&
				(!mandatory || !value.isEmpty()), label + "未填写或格式无效");
	}

	public static void checkRule(Rule rule) {
		require(rule != null && rule.kind() != null && rule.enabled() != null &&
						rule.requireReference() != null &&
						rule.requireParty() != null && rule.allowReturns() != null && rule.quarantineReturns() != null,
				"特殊收发规则不完整");
	}

	public static void checkRules(List<Rule> rules) {
		require(rules != null && rules.size() == 3, "特殊收发规则须完整包含三类业务");
		var kinds = EnumSet.noneOf(Kind.class);
		for (var rule : rules) {
			checkRule(rule);
			require(kinds.add(rule.kind()), "特殊收发规则类型重复");
		}
	}

	public static void checkPlan(Plan plan, Rule rule) {
		checkRule(rule);
		require(plan != null && plan.kind() != null && plan.direction() != null && plan.kind() == rule.kind() &&
				rule.enabled(), "当前仓库未启用该特殊收发业务");
		field(plan.partyCode(), 100, rule.requireParty(), "领用方或往来方");
		field(plan.reference(), 200, rule.requireReference(), "来源依据");
		field(plan.reason(), 500, true, "用途说明");
		require(plan.lines() != null && !plan.lines().isEmpty() && plan.lines().size() <= 200 &&
				plan.outputs() != null && plan.outputs().size() <= 200, "请填写有效申请物料，单类最多200行");
		require(plan.kind() == Kind.OUTSOURCE ?
						plan.direction() == Direction.OUT && !plan.outputs().isEmpty() : plan.outputs().isEmpty(),
				"外协须分别填写发料和交付计划；其它业务不填写加工交付");
		var ids = new HashSet<String>();
		for (var group : List.of(plan.lines(), plan.outputs())) {
			var materials = new HashSet<String>();
			for (var line : group) {
				require(line != null, "申请明细不能为空");
				field(line.materialCode(), 100, true, "物料");
				field(line.unit(), 100, true, "基本单位");
				quantity(line.quantity());
				require(materials.add(line.materialCode()), "同一计划中物料不能重复，请合并数量");
				if (line.lineId() != null) {
					id(line.lineId());
					require(ids.add(line.lineId()), "计划明细标识重复");
				}
			}
		}
	}

	public static List<Line> allLines(Plan plan) {
		return Stream.concat(plan.lines().stream(), plan.outputs().stream()).toList();
	}

	public static List<Balance> balance(Plan plan, List<Flow> flows) {
		var ids = new HashMap<String, Flow>();
		var executed = new HashMap<String, BigDecimal>();
		var returned = new HashMap<String, BigDecimal>();
		var bySource = new HashMap<String, BigDecimal>();
		for (var flow : flows) {
			require(flow != null && flow.id() != null && ids.put(flow.id(), flow) == null,
					"实际库存凭据重复或无效");
			quantity(flow.quantity());
		}
		var lines = allLines(plan);
		for (var flow : flows) {
			var line = lines.stream().filter(l -> Objects.equals(l.lineId(), flow.lineId())).findFirst()
					.orElseThrow(() -> new CheckedException("库存凭据不属于原计划明细"));
			boolean output = plan.outputs().contains(line);
			var direction = output ? Direction.IN : plan.direction();
			if (flow.sourceFlowId() == null) {
				require(flow.direction() == direction, "实际库存凭据方向不匹配");
				executed.merge(line.lineId(), quantity(flow.quantity()), BigDecimal::add);
			} else {
				var source = ids.get(flow.sourceFlowId());
				require(source != null && source.sourceFlowId() == null &&
								Objects.equals(source.lineId(), flow.lineId()) &&
								source.direction() != flow.direction(),
						"退回必须对应原实际收发流水");
				var total = bySource.merge(source.id(), quantity(flow.quantity()), BigDecimal::add);
				require(total.compareTo(quantity(source.quantity())) <= 0, "累计退回超过原实际收发数量");
				returned.merge(line.lineId(), quantity(flow.quantity()), BigDecimal::add);
			}
		}
		var result = new ArrayList<Balance>();
		for (var line : lines) {
			var done = executed.getOrDefault(line.lineId(), BigDecimal.ZERO);
			var back = returned.getOrDefault(line.lineId(), BigDecimal.ZERO);
			var net = done.subtract(back);
			var remaining = quantity(line.quantity()).subtract(net);
			require(net.signum() >= 0 && remaining.signum() >= 0, "实际净收发数量与申请不守恒");
			result.add(
					new Balance(line.lineId(), line.materialCode(), line.materialName(), line.unit(),
							line.quantity(),
							number(done), number(back), number(net), number(remaining),
							plan.outputs().contains(line)));
		}
		return List.copyOf(result);
	}

	public static BigDecimal returnable(Flow source, List<Flow> flows) {
		require(source != null && source.sourceFlowId() == null, "请选择原始实收实发流水");
		var back = flows.stream().filter(f -> Objects.equals(source.id(), f.sourceFlowId()))
				.map(f -> quantity(f.quantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
		var remaining = quantity(source.quantity()).subtract(back);
		require(remaining.signum() >= 0, "原流水累计退回数量异常");
		return remaining;
	}

	public static void checkExecution(Plan plan, List<Flow> flows, Action action, List<ExecutionLine> items) {
		require(items != null && !items.isEmpty() && items.size() <= 200, "请填写本次实际作业明细，最多200行");
		require(action == Action.EXECUTE || action == Action.RECEIVE_OUTPUT || action == Action.RETURN,
				"无效的实物操作");
		var balances = balance(plan, flows);
		var requested = new HashMap<String, BigDecimal>();
		for (var item : items) {
			require(item != null, "本次作业明细不能为空");
			var qty = quantity(item.quantity());
			id(item.lineId());
			var row = balances.stream().filter(b -> b.lineId().equals(item.lineId())).findFirst()
					.orElseThrow(() -> new CheckedException("本次物料不属于原计划"));
			if (action == Action.RETURN) {
				id(item.sourceFlowId());
				var source = flows.stream().filter(f -> f.id().equals(item.sourceFlowId())).findFirst()
						.orElseThrow(() -> new CheckedException("未找到原实际流水"));
				require(Objects.equals(source.lineId(), item.lineId()), "退回物料行与原实际流水不匹配");
				var sum = requested.merge(source.id(), qty, BigDecimal::add);
				require(sum.compareTo(returnable(source, flows)) <= 0, "本次退回超过原流水剩余可退数量");
			} else {
				require(item.sourceFlowId() == null &&
								(action == Action.EXECUTE ? !row.output() :
										plan.kind() == Kind.OUTSOURCE && row.output()),
						"本次操作与发料或交付计划不匹配");
				var sum = requested.merge(row.lineId(), qty, BigDecimal::add);
				require(sum.compareTo(new BigDecimal(row.remaining())) <= 0,
						"本次数量超过原计划剩余可办理数量");
			}
		}
	}

	public static void checkClosure(Plan plan, List<Flow> flows, List<CloseLine> declarations) {
		require(declarations != null && declarations.size() <= 10200, "请完整填写关闭核对数量");
		var balances = balance(plan, flows);
		if (plan.kind() != Kind.OUTSOURCE) {
			require(declarations.isEmpty(), "备件及免费收发不声明加工耗用");
			return;
		}
		var declared = new HashMap<String, BigDecimal>();
		for (var item : declarations) {
			require(item != null && (item.sourceFlowId() != null ^ item.outputLineId() != null),
					"关闭数量须对应原发料流水或交付计划行");
			var key = item.sourceFlowId() != null ? "F:" + item.sourceFlowId() : "O:" + item.outputLineId();
			id(item.sourceFlowId() != null ? item.sourceFlowId() : item.outputLineId());
			require(declared.put(key, quantity(item.quantity())) == null, "关闭核对项重复");
		}
		for (var flow : flows) {
			if (flow.sourceFlowId() != null ||
					!plan.lines().stream().anyMatch(l -> l.lineId().equals(flow.lineId()))) {
				continue;
			}
			var left = returnable(flow, flows);
			var amount = declared.remove("F:" + flow.id());
			require(left.compareTo(amount == null ? BigDecimal.ZERO : amount) == 0,
					"在外未退物料须逐流水核对实际耗用或处置，不能按交付收货推算");
		}
		for (var row : balances) {
			if (!row.output()) {
				continue;
			}
			var left = new BigDecimal(row.remaining());
			var amount = declared.remove("O:" + row.lineId());
			require(left.compareTo(amount == null ? BigDecimal.ZERO : amount) == 0,
					"交付实收和明确短交处置须与原交付计划守恒");
		}
		require(declared.isEmpty(), "关闭声明包含不属于原申请的流水或计划行");
	}
}
