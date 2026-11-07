package com.kitex.mom.wms.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.io.IOException;

import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.mapper.WmsInventoryVariancePolicyMapper;
import com.kitex.mom.wms.mapper.WmsWarehouseMenuMapper;
import com.kitex.mom.wms.service.impl.WmsInventoryWarehousePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;

/**
 * 新差异申请采用读取时的仓库规则；既有申请保留自己的冻结依据。
 */
@Service
@RequiredArgsConstructor
public class WmsInventoryVariancePolicyService {
	private final WmsInventoryVariancePolicyMapper policies;
	private final WmsInventoryWarehousePermission inventoryPermission;
	private final PermissionService permissions;
	private final WmsWarehouseMenuMapper menus;
	private final RemoteWarehouseService warehouses;

	public Policy effective(String warehouseCode) {
		Scope scope = scope(warehouseCode);
		inventoryPermission.require("view", scope.warehouse());
		return current(scope.warehouse());
	}

	/**
	 * 仅供已核验原计划范围的业务服务调用，HTTP读取必须走effective。
	 */
	public Policy current(String warehouseCode) {
		Scope scope = scope(warehouseCode);
		var row = policies.find(scope.tenant(), scope.factory(), scope.warehouse());
		if (row == null) {
			return new Policy(scope.warehouse(), "APPROVAL_ONLY", null, null, 0, "PRODUCT_DEFAULT");
		}
		require(row.version() > 0, "仓库差异规则版本无效");
		validate(row.mode(), row.quantityThreshold(), row.percentThreshold());
		return new Policy(scope.warehouse(), row.mode(), row.quantityThreshold(), row.percentThreshold(),
				row.version(), "WAREHOUSE_CONFIG");
	}

	@Transactional(rollbackFor = Exception.class)
	public Policy save(SaveCommand command) {
		require(command != null, "请填写盘点差异规则");
		Scope scope = scope(command.warehouseCode());
		inventoryPermission.require("add", scope.warehouse());
		require(permissions.hasPermission("wms_wmsWarehouseMenu_add"), "无仓库配置权限");
		validate(command.mode(), command.quantityThreshold(), command.percentThreshold());
		require(command.expectedVersion() >= 0 && command.expectedVersion() < Integer.MAX_VALUE,
				"差异规则版本无效");
		menus.lockConfiguration("WMS_WAREHOUSE_MENU:" + scope.tenant() + ":" + scope.factory());
		R<List<MdmWarehouseEntity>> response = warehouses.getMenuConfigurationWarehouses();
		require(response != null && response.getCode() == 0 && response.getData() != null,
				"仓库状态读取失败，请重试");
		int found = 0;
		for (var warehouse : response.getData()) {
			require(warehouse != null && Objects.equals(scope.tenant(), warehouse.getTenantId())
					&& Objects.equals(scope.factory(), warehouse.getFactoryCode())
					&& StrUtil.isNotBlank(warehouse.getWarehouseCode()), "仓库状态与当前组织不一致");
			if (scope.warehouse().equals(warehouse.getWarehouseCode())) {
				require(Boolean.TRUE.equals(warehouse.getStatus()), "仅允许配置已启用仓库");
				found++;
			}
		}
		require(found == 1, "仓库不存在或无权配置");
		var previous = current(scope.warehouse());
		require(previous.version() == command.expectedVersion(), "差异规则已更新，请重新读取后保存");
		var user = SecurityUtils.getUser();
		require(user != null && StrUtil.isNotBlank(user.getUsername()), "无法识别当前操作人");
		require(policies.upsert(scope.tenant(), scope.factory(), scope.warehouse(), command.mode(),
				command.quantityThreshold(), command.percentThreshold(), command.expectedVersion(),
				user.getUsername()) == 1, "差异规则已更新，请重新读取后保存");
		return new Policy(scope.warehouse(), command.mode(), command.quantityThreshold(), command.percentThreshold(),
				command.expectedVersion() + 1, "WAREHOUSE_CONFIG");
	}

	public static boolean requiresRecount(Policy policy, BigDecimal book, BigDecimal counted) {
		require(policy != null && book != null && counted != null && book.signum() >= 0 && counted.signum() >= 0,
				"盘点数量或差异规则无效");
		validate(policy.mode(), policy.quantityThreshold(), policy.percentThreshold());
		BigDecimal difference = counted.subtract(book).abs();
		if (difference.signum() == 0 || "APPROVAL_ONLY".equals(policy.mode())) {
			return false;
		}
		if ("ALL_VARIANCE".equals(policy.mode())) {
			return true;
		}
		// 交叉相乘保留精确边界，避免除法舍入把临界差异误判为未超限。
		return policy.quantityThreshold() != null && difference.compareTo(policy.quantityThreshold()) > 0
				|| policy.percentThreshold() != null && (book.signum() == 0
				||
				difference.multiply(BigDecimal.valueOf(100)).compareTo(book.multiply(policy.percentThreshold())) > 0);
	}

	private static void validate(String mode, BigDecimal quantity, BigDecimal percent) {
		require(List.of("APPROVAL_ONLY", "ALL_VARIANCE", "THRESHOLD").contains(mode == null ? "" : mode),
				"请选择有效的差异处理方式");
		if (!"THRESHOLD".equals(mode)) {
			require(quantity == null && percent == null, "当前处理方式不使用阈值");
			return;
		}
		require(quantity != null || percent != null, "请至少设置一个复盘阈值");
		require(validThreshold(quantity, "1000000000000", 6) && validThreshold(percent, "10000", 4),
				"数量阈值须为0至一万亿且最多6位小数，比例须为0至10000%且最多4位小数");
	}

	private static boolean validThreshold(BigDecimal value, String maximum, int scale) {
		return value == null || value.signum() >= 0 && value.compareTo(new BigDecimal(maximum)) <= 0
				&& value.stripTrailingZeros().scale() <= scale;
	}

	private Scope scope(String warehouseCode) {
		String warehouse = StrUtil.trim(warehouseCode);
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		require(tenant != null && StrUtil.isNotBlank(factory)
				&& !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
				&& !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()), "当前租户或工厂范围无效");
		require(StrUtil.isNotBlank(warehouse) && warehouse.length() <= 100 && !warehouse.contains("*"),
				"请选择有效仓库");
		return new Scope(tenant, factory, warehouse);
	}

	private record Scope(Long tenant, String factory, String warehouse) {
	}

	/**
	 * Decimal strings preserve the entire configured domain in browser JSON.
	 */
	public static final class PlainDecimalSerializer extends JsonSerializer<BigDecimal> {
		@Override
		public void serialize(BigDecimal value, JsonGenerator output, SerializerProvider provider) throws IOException {
			output.writeString(value.stripTrailingZeros().toPlainString());
		}
	}

	public record Policy(String warehouseCode, String mode,
	                     @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal quantityThreshold,
	                     @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal percentThreshold,
	                     int version, String source) {
	}

	public record SaveCommand(String warehouseCode, String mode, BigDecimal quantityThreshold,
	                          BigDecimal percentThreshold, int expectedVersion) {
	}
}
