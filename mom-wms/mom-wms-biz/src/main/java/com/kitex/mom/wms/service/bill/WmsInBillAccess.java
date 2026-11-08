package com.kitex.mom.wms.service.bill;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.wms.dto.WmsInBillDetailQueryDTO;
import com.kitex.mom.wms.enums.OtherInBillTypeEnum;
import com.kitex.mom.wms.enums.InBillTypeEnum;
import com.kitex.mom.wms.service.WmsMobileWarehouseAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import com.kitex.mom.wms.service.bill.WmsBillPermissionProofClient.Item;

import java.util.Objects;
import java.util.stream.Stream;

/**
 * 通用及采购/成品/生产退料/销售退货固定入口的真实组织、原单身份和仓库动作准入。
 * 通用按钮覆盖完整入库类型，固定族只处理实际本族；全部仓级及旧族码须证明菜单来源，不改变原库存规则。
 */
@Component
@RequiredArgsConstructor
public class WmsInBillAccess {

	private static final int MAX_IDS = 1000;
	private static final List<String> TYPES = Stream.concat(
					Arrays.stream(InBillTypeEnum.values()).map(InBillTypeEnum::getCode),
					Arrays.stream(OtherInBillTypeEnum.values()).map(OtherInBillTypeEnum::getCode)).distinct()
			.toList();

	private final JdbcTemplate db;
	private final PermissionService permissions;
	private final WmsMobileWarehouseAccess factories;
	private final WmsBillPermissionProofs proofs;

	/**
	 * 固定HTTP入口授权族，不从查询参数或正文绑定。
	 */
	public enum Family {
		WAREHOUSE(null, "warehouse"), PURCHASE("purchase", "purchase"), PRODUCT("product", "product"),
		PRODUCE_RETURN("return", "return"), SALES_RETURN("sales_return", "salesReturn");

		/**
		 * 固定原单类型；通用入口为null并保留正式类型全集。
		 */
		private final String type;
		/**
		 * Admin证明使用的固定菜单族。
		 */
		private final String proofFamily;

		/**
		 * 权限旧族码独立于持久类型，销售退货为salesReturn而实际类型为sales_return。
		 */
		private final String permissionFamily;

		Family(String type, String permissionFamily) {
			this.type = type;
			this.permissionFamily = permissionFamily;
			this.proofFamily = "in_bill_" + permissionFamily;
		}
	}

	/**
	 * 通用单据的固定动作，不能由外部提供权限前缀。
	 */
	public enum Action {
		VIEW("view"), ADD("add"), EDIT("edit"), DELETE("del"), COMMIT("commit"),
		REVIEW("review"), ADD_REVIEW("add_review"), EXPORT("export");

		/**
		 * 固定菜单动作码，用于组合实际仓库权限。
		 */
		private final String code;

		Action(String code) {
			this.code = code;
		}
	}

	/**
	 * 已核实名用户工厂准入的实际组织，租户不接受请求覆盖。
	 *
	 * @param tenant  当前实名认证租户主键
	 * @param factory 当前人员获准读写的实际工厂编码
	 */
	public record Scope(Long tenant, String factory) {
	}

	/**
	 * 原单身份及冻结归属；类型、仓库、关联通知和状态均取实际表。
	 *
	 * @param id        原入库单或来源通知的真实主键
	 * @param type      实际持久业务类型；销售退货为sales_return
	 * @param warehouse 原单实际仓库编码
	 * @param noticeId  原单关联通知身份；来源通知本身为空
	 * @param state     原单当前状态码
	 */
	public record Original(Long id, String type, String warehouse, Long noticeId, String state) {
	}

	/**
	 * 新增沿实际工厂写授权与目标仓通用新增/直接审核权限。
	 *
	 * @param warehouse 目标实际仓库编码，不允许通配符
	 * @param pass      true要求独立直接审核权限，false要求普通新增权限
	 * @return 已核准的组织，供正文组织字段由服务端覆盖
	 */
	public Scope requireCreate(Family family, String warehouse, boolean pass) {
		Scope scope = scope(true);
		require(validWarehouse(warehouse), "仓库编码不正确");
		preload(scope, family, List.of(warehouse), List.of(pass ? Action.ADD_REVIEW : Action.ADD));
		requirePermission(scope, family, pass ? Action.ADD_REVIEW : Action.ADD, warehouse);
		return scope;
	}

	/**
	 * 全批读取或按ID升序锁定实际入库单，缺失、越组织或任一仓无权时全部拒绝。
	 * 写入调用必须由外层事务覆盖，不能以正文类型决定原单处理器。
	 *
	 * @param supplied 原单主键集合，重复主键合并
	 * @param action   原仓必须具备的固定动作
	 * @param lock     true核工厂写权限并锁单，false核工厂读权限
	 * @return 已完整核准且按ID升序的实际原单集合
	 */
	public List<Original> originals(Family family, Collection<Long> supplied, Action action, boolean lock) {
		List<Long> ids = identifiers(supplied);
		Scope scope = scope(lock);
		List<Original> rows = db.query(
				"SELECT id,bill_type,warehouse_code,notice_id,state FROM mom_wms.wms_in_bill"
						+ " WHERE tenant_id=? AND factory_code=? AND del_flag='0' AND id IN ("
						+ placeholders(ids.size()) + ") ORDER BY id" + (lock ? " FOR UPDATE" : ""),
				(row, index) -> new Original(row.getLong("id"), row.getString("bill_type"),
						row.getString("warehouse_code"), row.getObject("notice_id", Long.class),
						row.getString("state")), arguments(scope, ids).toArray());
		require(rows.size() == ids.size(), "所选入库单已失效或不属于当前组织，请刷新后重选");
		rows.forEach(row -> requireType(family, row.type()));
		preload(scope, family, rows.stream().map(Original::warehouse).toList(), List.of(action));
		for (Original row : rows) {
			requirePermission(scope, family, action, row.warehouse());
		}
		return rows;
	}

	/**
	 * 关联通知按实际组织、仓库和类型核出入库权限，创建时同时锁原通知。
	 * 调拨和盘点没有标准通知出入库按钮映射，关联新增和来源读取须走原专用入口，不能借查看权限放行。
	 *
	 * @param id   原通知主键
	 * @param lock true用于写事务内关联建单，false用于建单来源读取
	 * @return 已核准原通知的实际类型及仓库
	 */
	public Original notice(Family family, Long id, boolean lock) {
		Scope scope = scope(lock);
		List<Long> ids = identifiers(Collections.singletonList(id));
		List<Original> rows = db.query(
				"SELECT id,bill_type,warehouse_code,state FROM mom_wms.wms_in_notice"
						+ " WHERE tenant_id=? AND factory_code=? AND del_flag='0' AND id=?"
						+ (lock ? " FOR UPDATE" : ""),
				(row, index) -> new Original(row.getLong("id"), row.getString("bill_type"),
						row.getString("warehouse_code"), null, row.getString("state")),
				arguments(scope, ids).toArray());
		require(rows.size() == 1, "原入库通知不存在或不属于当前组织");
		Original row = rows.get(0);
		requireType(family, row.type());
		requireNoticePermission(scope, row);
		return row;
	}

	/**
	 * 通知读取属于创建准备，除通知inout外还须有当前入口新增或直接审核权限；读取不修改业务事实。
	 *
	 * @param warehouse 已从原通知读得的实际仓库
	 */
	public void requireSourceSelection(Family family, String warehouse) {
		Scope scope = scope(false);
		require(validWarehouse(warehouse), "仓库编码不正确");
		preload(scope, family, List.of(warehouse), List.of(Action.ADD, Action.ADD_REVIEW));
		require(validWarehouse(warehouse)
						&& (allowed(scope, family, Action.ADD, warehouse) ||
						allowed(scope, family, Action.ADD_REVIEW, warehouse)),
				"当前账号无该仓库入库新增权限");
	}

	/**
	 * 主表查询、统计和导出固定实际组织和入口允许的类型，空授权显式返回零行。
	 * 历史仓范围从真实原单枚举，不依赖今日仓库主数据是否启用。
	 *
	 * @param wrapper 保留用户筛选的查询条件，由本方法追加强制范围
	 * @param action  本次查看或导出动作
	 */
	public void restrictMain(Family family, QueryWrapper<?> wrapper, Action action) {
		Scope scope = scope(false);
		List<String> warehouses = warehouses(scope, family, action);
		wrapper.eq("tenant_id", scope.tenant()).eq("factory_code", scope.factory())
				.in("bill_type", types(family));
		if (warehouses.isEmpty()) {
			wrapper.apply("1=0");
		} else {
			wrapper.in("warehouse_code", warehouses);
		}
	}

	/**
	 * 明细SQL使用服务端覆盖的组织与授权仓，外部scope和billTypes值不能扩大查询。
	 * 显式业务筛选仍保留，非法业务类型拒绝；空授权集合由XML转换为零行条件。
	 *
	 * @param query  明细查询正文，将被覆盖可信范围字段
	 * @param action 本次查看或导出动作
	 */
	public void restrictDetails(Family family, WmsInBillDetailQueryDTO query, Action action) {
		require(query != null, "请提交入库明细查询条件");
		queryType(family, query.getBillType());
		if (family.type != null) {
			query.setBillType(family.type);
		}
		Scope scope = scope(false);
		query.setScopeTenantId(scope.tenant());
		query.setScopeFactoryCode(scope.factory());
		query.setPermittedWarehouseCodes(warehouses(scope, family, action));
		query.setBillTypes(types(family));
	}

	/**
	 * 指定导出身份整批预检，明细ID必须关联同组织、同仓主单，不能只导出其中有权的部分。
	 *
	 * @param supplied 所选主单或明细ID，空选择由后续查询条件决定
	 * @param detail   true表示明细ID，false表示主单ID
	 */
	public void checkExportIds(Family family, Long[] supplied, boolean detail) {
		if (supplied == null || supplied.length == 0) {
			return;
		}
		List<Long> ids = identifiers(Arrays.asList(supplied));
		if (!detail) {
			originals(family, ids, Action.EXPORT, false);
			return;
		}
		Scope scope = scope(false);
		List<Original> rows = db.query(
				"SELECT d.id,h.bill_type,h.warehouse_code,h.notice_id,h.state"
						+ " FROM mom_wms.wms_in_bill_detail d JOIN mom_wms.wms_in_bill h ON h.id=d.bill_id"
						+ " AND h.tenant_id=d.tenant_id AND h.factory_code=d.factory_code"
						+ " AND h.warehouse_code=d.warehouse_code"
						+ " WHERE d.tenant_id=? AND d.factory_code=? AND d.del_flag='0' AND h.del_flag='0'"
						+ " AND d.id IN (" + placeholders(ids.size()) + ") ORDER BY d.id",
				(row, index) -> new Original(row.getLong("id"), row.getString("bill_type"),
						row.getString("warehouse_code"), row.getObject("notice_id", Long.class),
						row.getString("state")), arguments(scope, ids).toArray());
		require(rows.size() == ids.size(), "所选明细已失效或不属于当前组织，请刷新后重选");
		rows.forEach(row -> requireType(family, row.type()));
		preload(scope, family, rows.stream().map(Original::warehouse).toList(), List.of(Action.EXPORT));
		for (Original row : rows) {
			requirePermission(scope, family, Action.EXPORT, row.warehouse());
		}
	}

	/**
	 * 修改保持原业务和关联通知；跨仓还须核目标仓同等编辑权限。
	 */
	public void requireEdit(Family family, Original row, String type, Long noticeId, String warehouse) {
		requireType(family, type);
		require(Objects.equals(row.type(), type), "入库单业务类型不能修改");
		require(Objects.equals(row.noticeId(), noticeId), "入库单关联通知不能修改");
		Scope scope = scope(true);
		require(validWarehouse(warehouse), "仓库编码不正确");
		preload(scope, family, List.of(warehouse), List.of(Action.EDIT));
		requirePermission(scope, family, Action.EDIT, warehouse);
	}

	/**
	 * 完整正整数文本ID保留Long精度，重复合并、升序办理，限制一次1至1000张。
	 */
	public List<Long> identifiers(String[] supplied) {
		require(supplied != null && supplied.length > 0 && supplied.length <= MAX_IDS,
				"请选择1至1000张实际入库单");
		List<Long> ids = new ArrayList<>();
		for (String id : supplied) {
			require(id != null && id.matches("[1-9][0-9]{0,18}")
							&& (id.length() < 19 || id.compareTo("9223372036854775807") <= 0),
					"入库单标识无效，请从列表重新选择");
			ids.add(Long.valueOf(id));
		}
		return identifiers(ids);
	}

	/**
	 * 写入必须使用正式入库类型，保留采购、成品、生产退料、销售退货及既有通用子类型。
	 */
	public void requireType(Family family, String type) {
		require(family != null && type != null && TYPES.contains(type)
				&& (family.type == null || family.type.equals(type)), "入库业务类型与当前入口不一致");
	}

	/**
	 * 可选查询类型不填时覆盖正式类型全集，填写时必须为正式类型。
	 */
	public void queryType(Family family, String type) {
		if (type != null && !type.isBlank()) {
			requireType(family, type);
		}
	}

	private Scope scope(boolean write) {
		factories.requireFactory(write);
		return new Scope(TenantContextHolder.getTenantId(), FactoryContextHolder.getFactoryCode());
	}

	private List<Long> identifiers(Collection<Long> supplied) {
		require(supplied != null && !supplied.isEmpty() && supplied.size() <= MAX_IDS,
				"请选择1至1000张实际入库单");
		require(supplied.stream().allMatch(id -> id != null && id > 0), "入库单标识无效，请刷新后重选");
		return supplied.stream().distinct().sorted().toList();
	}

	private List<String> warehouses(Scope scope, Family family, Action action) {
		List<Object> args = arguments(scope, List.of());
		args.addAll(types(family));
		List<String> rows = db.query(
				"SELECT DISTINCT warehouse_code FROM mom_wms.wms_in_bill"
						+ " WHERE tenant_id=? AND factory_code=? AND del_flag='0' AND bill_type IN ("
						+ placeholders(types(family).size()) + ") ORDER BY warehouse_code LIMIT 10001",
				(row, index) -> row.getString("warehouse_code"), args.toArray());
		require(rows.size() <= 10000, "仓库范围过大，请联系管理员核对");
		preload(scope, family, rows, List.of(action));
		return rows.stream()
				.filter(warehouse -> validWarehouse(warehouse) && allowed(scope, family, action, warehouse))
				.toList();
	}

	/**
	 * 固定入口新增/修改可省略类型；外部声明其它类型则拒绝，不允许变更授权族。
	 */
	public void requireInputType(Family family, com.kitex.mom.wms.entity.WmsInBillEntity input) {
		if (family.type != null && input.getBillType() == null) {
			input.setBillType(family.type);
		}
		requireType(family, input.getBillType());
	}

	private List<String> types(Family family) {
		require(family != null, "入库业务入口不正确");
		return family.type == null ? TYPES : List.of(family.type);
	}

	private void requirePermission(Scope scope, Family family, Action action, String warehouse) {
		require(validWarehouse(warehouse) && allowed(scope, family, action, warehouse),
				"当前账号无该仓库的入库操作权限");
	}

	/**
	 * 批量预热全部仓级及固定族候选，后续逐行只读请求内证明。
	 * 普通仓也可能在不同工厂同码，不能只凭去重后的权限字符串放行。
	 */
	private void preload(Scope scope, Family family, Collection<String> warehouses,
	                     Collection<Action> actions) {
		List<Item> items = new ArrayList<>();
		for (String warehouse : warehouses) {
			if (!validWarehouse(warehouse)) {
				continue;
			}
			for (Action action : actions) {
				String base = "wms_bill_" + action.code;
				if (permissions.hasPermission(base, base + "_in")) {
					continue;
				}
				String candidate = base + "_in_" + warehouse;
				if (permissions.hasPermission(candidate)) {
					items.add(item(scope, Family.WAREHOUSE, action, warehouse, candidate));
				}
				if (family != Family.WAREHOUSE) {
					for (String permission : familyPermissions(base, family, warehouse)) {
						if (permissions.hasPermission(permission)) {
							items.add(item(scope, family, action, warehouse, permission));
						}
					}
				}
			}
		}
		proofs.preload(items);
	}

	private boolean allowed(Scope scope, Family family, Action action, String warehouse) {
		String base = "wms_bill_" + action.code;
		if (permissions.hasPermission(base, base + "_in")) {
			return true;
		}
		String candidate = base + "_in_" + warehouse;
		if (permissions.hasPermission(candidate)
				&& proofs.allowed(item(scope, Family.WAREHOUSE, action, warehouse, candidate))) {
			return true;
		}
		if (family != Family.WAREHOUSE) {
			for (String permission : familyPermissions(base, family, warehouse)) {
				if (permissions.hasPermission(permission) &&
						proofs.allowed(item(scope, family, action, warehouse, permission))) {
					return true;
				}
			}
		}
		return false;
	}

	private List<String> familyPermissions(String base, Family family, String warehouse) {
		return List.of(base + "_in_" + family.permissionFamily, base + "_" + family.permissionFamily,
				base + "_" + family.permissionFamily + "_" + warehouse,
				base + "_in_" + family.permissionFamily + "_" + warehouse);
	}

	/**
	 * 建单来源按实际通知族证明inout，不借查看权限；旧销售退货别名须沿真正入库通知页面。
	 * 调拨、盘点等没有标准通知出入库菜单，保留原专用来源入口，不推断一个通知族。
	 */
	private void requireNoticePermission(Scope scope, Original row) {
		require(validWarehouse(row.warehouse()), "原通知仓库编码无效");
		String proofFamily = switch (row.type()) {
			case "purchase", "product", "return" -> "in_notice_" + row.type();
			case "sales_return" -> "in_notice_salesReturn";
			case "other_in" -> "in_notice_other";
			default -> throw new CheckedException("该通知请从原专用业务入口办理，通用入口不支持关联新增");
		};
		String base = "wms_notice_inout";
		if (permissions.hasPermission(base)) {
			return;
		}
		List<String> aliases = "sales_return".equals(row.type()) ? List.of("sales_return", "salesReturn") : List.of(
				row.type());
		List<Item> held = new ArrayList<>();
		for (String alias : aliases) {
			for (String permission : List.of(base + "_" + alias, base + "_" + alias + "_" + row.warehouse())) {
				if (permissions.hasPermission(permission)) {
					held.add(new Item(proofFamily, "inout", row.warehouse(), scope.factory(), permission));
				}
			}
		}
		proofs.preload(held);
		require(held.stream().anyMatch(proofs::allowed), "当前账号无该仓库通知出入库权限");
	}

	private Item item(Scope scope, Family family, Action action, String warehouse, String permission) {
		return new Item(family.proofFamily, action.code, warehouse, scope.factory(), permission);
	}

	/**
	 * 与Admin证明精确使用同一安全编码：允许内部普通空格，不接受路径、通配或其它空白。
	 * 历史无效仓在枚举和预热时逐项排除，不能使其他合法仓的读取失败。
	 */
	private boolean validWarehouse(String warehouse) {
		return warehouse != null && !warehouse.isBlank() && warehouse.equals(warehouse.trim())
				&& warehouse.length() <= 100 && !List.of(".", "..").contains(warehouse)
				&& warehouse.codePoints().noneMatch(code -> Character.isISOControl(code)
				|| (code != ' ' && (Character.isWhitespace(code) || Character.isSpaceChar(code)))
				|| "/\\?#%*".indexOf(code) >= 0);
	}

	private List<Object> arguments(Scope scope, Collection<Long> ids) {
		List<Object> values = new ArrayList<>();
		values.add(scope.tenant());
		values.add(scope.factory());
		values.addAll(ids);
		return values;
	}

	private String placeholders(int size) {
		return String.join(",", Collections.nCopies(size, "?"));
	}

	private void require(boolean valid, String message) {
		if (!valid) {
			throw new CheckedException(message);
		}
	}
}
