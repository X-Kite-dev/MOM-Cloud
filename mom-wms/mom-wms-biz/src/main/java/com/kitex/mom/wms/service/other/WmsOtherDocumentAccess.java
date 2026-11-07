package com.kitex.mom.wms.service.other;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.wms.dto.WmsInBillDetailQueryDTO;
import com.kitex.mom.wms.dto.WmsInNoticeDetailQueryDTO;
import com.kitex.mom.wms.enums.OtherInBillTypeEnum;
import com.kitex.mom.wms.enums.OtherOutBillTypeEnum;
import com.kitex.mom.wms.service.WmsOtherBusinessTypes;
import com.kitex.mom.wms.service.bill.WmsBillPermissionProofs;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 管理端其他收发的真实组织、业务族和仓库按钮权限边界。
 * 原单及明细身份只读实际表；写入前由办理服务在同一事务加锁，不信任正文声明的类型或仓库。
 */
@Component
@RequiredArgsConstructor
public class WmsOtherDocumentAccess {
	private static final int MAX_IDS = 1000;
	private static final List<String> OUTBOUND_ACTIONS = List.of(
			"view", "add", "edit", "del", "commit", "review", "add_review", "export");
	private final JdbcTemplate db;
	private final PermissionService permissions;
	private final RemoteMdmFactoryAccessService factories;
	private final WmsBillPermissionProofs permissionProofs;

	/**
	 * 固定持久化对象，不接受请求中的表名、方向或权限前缀。
	 */
	public enum Kind {
		IN_BILL("wms_in_bill", "bill_id", true, false),
		OUT_BILL("wms_out_bill", "bill_id", false, false),
		IN_NOTICE("wms_in_notice", "notice_id", true, true),
		OUT_NOTICE("wms_out_notice", "notice_id", false, true);

		private final String table;
		private final String detailParent;
		private final boolean inbound;
		private final boolean notice;

		Kind(String table, String detailParent, boolean inbound, boolean notice) {
			this.table = table;
			this.detailParent = detailParent;
			this.inbound = inbound;
			this.notice = notice;
		}

		private List<String> types() {
			return inbound
					? Stream.concat(Stream.of("other_in"), Arrays.stream(OtherInBillTypeEnum.values())
					.map(OtherInBillTypeEnum::getCode)).toList()
					: Stream.concat(Stream.of("other_out"), Arrays.stream(OtherOutBillTypeEnum.values())
					.map(OtherOutBillTypeEnum::getCode)).toList();
		}
	}

	/**
	 * 原单查询的已验证实际组织，不接受外部指定租户。
	 */
	private record Scope(Long tenant, String factory) {
	}

	/**
	 * @param id        实际原单身份，保留Long原值
	 * @param type      已保存业务类型，不取请求正文
	 * @param warehouse 实际原仓库，供按钮权限核对
	 */
	public record Original(Long id, String type, String warehouse) {
	}

	/**
	 * 正文新增沿实际工厂和目标仓操作权限，真实业务类型仍由原处理器校验。
	 */
	public void requireCreate(Kind kind, String warehouse, String action) {
		scope(true);
		requirePermission(kind, action, warehouse);
	}

	/**
	 * 批量读取其他出库原合同的仓库动作能力；只核人员、工厂读取和菜单来源，不代表库存或业务已放行。
	 * 请求未携带角色或许可结果，发送写动作时仍须重新核验工厂写权及原单状态。
	 */
	public Map<String, List<String>> outboundCapabilities(List<String> supplied) {
		scope(false);
		require(supplied != null && supplied.size() <= 100
						&& supplied.stream().allMatch(this::validOutboundWarehouse),
				"请提交有效的仓库范围，单次最多100个");
		List<String> warehouses = supplied.stream().distinct().toList();
		preloadOutboundChecks(Kind.OUT_BILL, warehouses, OUTBOUND_ACTIONS);
		Map<String, List<String>> result = new LinkedHashMap<>();
		for (String warehouse : warehouses) {
			result.put(warehouse, OUTBOUND_ACTIONS.stream()
					.filter(action -> allowed(Kind.OUT_BILL, action, warehouse)).toList());
		}
		return result;
	}

	/**
	 * 读取或锁定全部原单，缺失、越仓或跨族时整批拒绝；写调用须在外层业务事务内。
	 */
	public List<Original> originals(Kind kind, Collection<Long> supplied, String action, boolean lock) {
		var ids = identifiers(supplied);
		var scope = scope(lock);
		var args = arguments(scope, ids);
		var rows = db.query("SELECT id,bill_type,warehouse_code FROM mom_wms." + kind.table
						+ " WHERE tenant_id=? AND factory_code=? AND del_flag='0' AND id IN ("
						+ placeholders(ids.size()) + ") ORDER BY id" + (lock ? " FOR UPDATE" : ""),
				(row, index) -> new Original(row.getLong("id"), row.getString("bill_type"),
						row.getString("warehouse_code")), args.toArray());
		require(rows.size() == ids.size(), "所选单据已失效或不属于当前组织，请刷新后重选");
		preloadOutboundChecks(kind, rows.stream().map(Original::warehouse).toList(), List.of(action));
		for (var row : rows) {
			requireType(kind, row.type());
			requirePermission(kind, action, row.warehouse());
		}
		return rows;
	}

	/**
	 * 批量删除沿原正整数文本ID，重复身份合并；拒绝空批、溢出和过大批次。
	 */
	public List<Long> identifiers(String[] supplied) {
		require(supplied != null && supplied.length > 0 && supplied.length <= MAX_IDS,
				"请选择1至1000张实际单据");
		var ids = new ArrayList<Long>();
		for (String value : supplied) {
			require(value != null && value.matches("[1-9][0-9]{0,18}")
							&& (value.length() < 19 || value.compareTo("9223372036854775807") <= 0),
					"单据标识无效，请从列表重新选择");
			ids.add(Long.valueOf(value));
		}
		return identifiers(ids);
	}

	/**
	 * 主表的查询、统计和导出均追加实际组织/类型/授权仓库；空授权明确返回零行。
	 */
	public void restrictMain(Kind kind, QueryWrapper<?> wrapper, String action) {
		var scope = scope(false);
		var warehouses = warehouses(kind, scope, action);
		wrapper.eq("tenant_id", scope.tenant()).eq("factory_code", scope.factory())
				.in("bill_type", kind.types());
		if (warehouses.isEmpty()) {
			wrapper.apply("1=0");
		} else {
			wrapper.in("warehouse_code", warehouses);
		}
	}

	/**
	 * 单据明细的可信范围由服务覆盖，不接受请求自行声明的授权仓库或组织。
	 */
	public void restrictDetails(Kind kind, WmsInBillDetailQueryDTO query, String action) {
		require(!kind.notice && query != null, "单据明细查询参数无效");
		var scope = scope(false);
		query.setScopeTenantId(scope.tenant());
		query.setScopeFactoryCode(scope.factory());
		query.setPermittedWarehouseCodes(warehouses(kind, scope, action));
		query.setBillTypes(kind.types());
	}

	/**
	 * 通知明细使用与主表相同的真实组织和授权仓库，保留原查询条件。
	 */
	public void restrictDetails(Kind kind, WmsInNoticeDetailQueryDTO query, String action) {
		require(kind.notice && query != null, "通知明细查询参数无效");
		var scope = scope(false);
		query.setScopeTenantId(scope.tenant());
		query.setScopeFactoryCode(scope.factory());
		query.setPermittedWarehouseCodes(warehouses(kind, scope, action));
		query.setBillTypes(kind.types());
	}

	/**
	 * 指定导出ID须全部属于本入口；明细ID与主单ID分别核对，不能互换。
	 */
	public void checkExportIds(Kind kind, Long[] supplied, boolean detail) {
		if (supplied == null || supplied.length == 0) {
			return;
		}
		var ids = identifiers(Arrays.asList(supplied));
		if (!detail) {
			originals(kind, ids, "export", false);
			return;
		}
		var scope = scope(false);
		var rows = db.query("SELECT d.id,h.bill_type,h.warehouse_code FROM mom_wms." + kind.table
						+ "_detail d JOIN mom_wms." + kind.table + " h ON h.id=d." + kind.detailParent
						+ " AND h.tenant_id=d.tenant_id AND h.factory_code=d.factory_code"
						+ " AND h.warehouse_code=d.warehouse_code"
						+ " WHERE d.tenant_id=? AND d.factory_code=? AND d.del_flag='0' AND h.del_flag='0'"
						+ " AND d.id IN (" + placeholders(ids.size()) + ") ORDER BY d.id",
				(row, index) -> new Original(row.getLong("id"), row.getString("bill_type"),
						row.getString("warehouse_code")), arguments(scope, ids).toArray());
		require(rows.size() == ids.size(), "所选明细已失效或不属于当前组织，请刷新后重选");
		preloadOutboundChecks(kind, rows.stream().map(Original::warehouse).toList(), List.of("export"));
		for (var row : rows) {
			requireType(kind, row.type());
			requirePermission(kind, "export", row.warehouse());
		}
	}

	/**
	 * 修改只沿原单类型；草稿改仓还须具备目标仓相同操作权限，原处理器继续核目标仓及状态。
	 */
	public void requireEdit(Kind kind, Original original, String claimedType, String targetWarehouse) {
		requireType(kind, claimedType);
		require(Objects.equals(original.type(), claimedType), "单据业务类型不能修改，请从原业务入口办理");
		if (targetWarehouse != null && !targetWarehouse.equals(original.warehouse())) {
			requirePermission(kind, kind.notice ? "add" : "edit", targetWarehouse);
		}
	}

	private List<Long> identifiers(Collection<Long> supplied) {
		require(supplied != null && !supplied.isEmpty() && supplied.size() <= MAX_IDS,
				"请选择1至1000张实际单据");
		require(supplied.stream().allMatch(id -> id != null && id > 0), "请从列表重新选择有效单据");
		return supplied.stream().distinct().sorted().toList();
	}

	private List<Object> arguments(Scope scope, Collection<Long> ids) {
		var args = new ArrayList<Object>();
		args.add(scope.tenant());
		args.add(scope.factory());
		args.addAll(ids);
		return args;
	}

	private String placeholders(int size) {
		return String.join(",", Collections.nCopies(size, "?"));
	}

	private void requireType(Kind kind, String type) {
		if (kind.inbound) {
			WmsOtherBusinessTypes.requireInbound(type);
		} else {
			WmsOtherBusinessTypes.requireOutbound(type);
		}
	}

	/**
	 * 授权仓库从本组织真实原单枚举；停用/删除的仓库历史不依赖今日主数据重建。
	 */
	private List<String> warehouses(Kind kind, Scope scope, String action) {
		var args = arguments(scope, List.of());
		args.addAll(kind.types());
		var rows = db.query("SELECT DISTINCT warehouse_code FROM mom_wms." + kind.table
						+ " WHERE tenant_id=? AND factory_code=? AND del_flag='0' AND bill_type IN ("
						+ placeholders(kind.types().size()) + ") ORDER BY warehouse_code LIMIT 10001",
				(row, index) -> row.getString("warehouse_code"), args.toArray());
		require(rows.size() <= 10000, "仓库范围过大，请联系管理员核对");
		preloadOutboundChecks(kind, rows.stream().filter(warehouse -> validWarehouse(kind, warehouse)).toList(),
				List.of(action));
		return rows.stream().filter(warehouse -> validWarehouse(kind, warehouse)
				&& allowed(kind, action, warehouse)).toList();
	}

	private void requirePermission(Kind kind, String action, String warehouse) {
		require(validWarehouse(kind, warehouse), "请选择有效仓库");
		preloadOutboundChecks(kind, List.of(warehouse), List.of(action));
		require(validWarehouse(kind, warehouse) && allowed(kind, action, warehouse),
				"当前账号无该仓库的单据操作权限");
	}

	/**
	 * 对齐仓库生成菜单及既有全局其他单据页的明确权限，不接受请求里的通配仓库。
	 */
	private boolean allowed(Kind kind, String action, String warehouse) {
		String base = (kind.notice ? "wms_notice_" : "wms_bill_") + action;
		String family = kind.inbound ? "other_in" : "other_out";
		String direction = kind.inbound ? "in" : "out";
		if (permissions.hasPermission(base) || (kind != Kind.OUT_BILL
				&& permissions.hasPermission(base + "_" + family, base + "_" + family + "_" + warehouse))) {
			return true;
		}
		if (kind.notice) {
			return false;
		}
		if (kind.inbound) {
			return permissions.hasPermission(base + "_" + direction,
					base + "_" + direction + "_other", base + "_" + direction + "_" + warehouse);
		}
		if (permissions.hasPermission(base + "_out")) {
			return true;
		}
		for (String permission : outboundFamilyPermissions(base, warehouse)) {
			if (permissions.hasPermission(permission)
					&& permissionProofs.hasProof("other", action, warehouse, permission)) {
				return true;
			}
		}
		String warehouseAlias = base + "_out_" + warehouse;
		return permissions.hasPermission(warehouseAlias)
				&& (permissionProofs.hasProof("warehouse", action, warehouse, warehouseAlias)
				|| permissionProofs.hasProof("other", action, warehouse, warehouseAlias));
	}

	/**
	 * 全部出库仓级候选须证明实际工厂及仓根，其他族全局码也须核真实页面来源。
	 * canonical全局other_out可与旧other_仓out重名，不能仅按字符串视为全局；通知和入库合同不变。
	 * 先合并本次批量原单候选再调用Admin，逐行核权只读本请求证明，不产生N+1远程调用。
	 */
	private void preloadOutboundChecks(Kind kind, Collection<String> warehouses, Collection<String> actions) {
		if (kind != Kind.OUT_BILL || warehouses.isEmpty()) {
			return;
		}
		List<WmsBillPermissionProofs.Check> checks = new ArrayList<>();
		for (String warehouse : warehouses.stream().filter(this::validOutboundWarehouse).distinct().toList()) {
			for (String action : actions) {
				String base = "wms_bill_" + action;
				if (permissions.hasPermission(base, base + "_out")) {
					continue;
				}
				for (String permission : outboundFamilyPermissions(base, warehouse)) {
					if (permissions.hasPermission(permission)) {
						checks.add(new WmsBillPermissionProofs.Check("other", action, warehouse, permission));
					}
				}
				String warehouseAlias = base + "_out_" + warehouse;
				if (permissions.hasPermission(warehouseAlias)) {
					checks.add(
							new WmsBillPermissionProofs.Check("warehouse", action, warehouse, warehouseAlias));
					checks.add(new WmsBillPermissionProofs.Check("other", action, warehouse, warehouseAlias));
				}
			}
		}
		if (!checks.isEmpty()) {
			permissionProofs.prefetch(checks);
		}
	}

	/**
	 * 保留其他出库既有canonical全局/带仓及方向别名，不增加其他族权限形态。
	 */
	private List<String> outboundFamilyPermissions(String base, String warehouse) {
		return List.of(base + "_other_out", base + "_other_out_" + warehouse, base + "_out_other");
	}

	/**
	 * 仅其他出库单使用证明协议的严格编码；入库单及入/出库通知保留原仓码合同。
	 * 这些对象未接入本轮出库证明，不能因证明协议的路径限制隐藏其历史仓或拒绝原单。
	 */
	private boolean validWarehouse(Kind kind, String value) {
		if (kind == Kind.OUT_BILL) {
			return validOutboundWarehouse(value);
		}
		return value != null && !value.isBlank() && value.equals(value.trim())
				&& value.length() <= 100 && !value.matches(".*[?*].*");
	}

	/**
	 * 其他出库单及其能力接口与Admin使用相同安全编码；历史不安全仓在证明预热前排除。
	 */
	private boolean validOutboundWarehouse(String value) {
		return value != null && !value.isBlank() && value.equals(value.trim())
				&& value.length() <= 100 && !List.of(".", "..").contains(value)
				&& value.codePoints().noneMatch(code -> Character.isISOControl(code)
				|| (code != ' ' && (Character.isWhitespace(code) || Character.isSpaceChar(code)))
				|| "/\\?#%*".indexOf(code) >= 0);
	}

	/**
	 * 不仅接收factory-code，还按当前实名账号核对MDM实际工厂读/写授权。
	 */
	private Scope scope(boolean write) {
		var tenant = TenantContextHolder.getTenantId();
		var factory = FactoryContextHolder.getFactoryCode();
		var user = SecurityUtils.getUser();
		require(tenant != null && tenant > 0 && !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
						&& !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
						&& factory != null && !factory.isBlank() && user != null && user.getId() != null
						&& Objects.equals(user.getTenantId(), tenant),
				"请登录实际租户并选择已授权工厂");
		var result = factories.check(new MdmFactoryAccessCheck().setUserId(user.getId()).setFactoryCode(factory)
				.setRequiredPermission(write ? "write" : "read"));
		require(result != null && result.isOk() && Boolean.TRUE.equals(result.getData()), "未获得当前工厂授权");
		return new Scope(tenant, factory);
	}

	private void require(boolean valid, String message) {
		if (!valid) {
			throw new CheckedException(message);
		}
	}
}
