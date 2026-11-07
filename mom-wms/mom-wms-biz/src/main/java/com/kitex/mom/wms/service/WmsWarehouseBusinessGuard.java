package com.kitex.mom.wms.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.entity.WmsWarehouseBizEntity;
import com.kitex.mom.wms.enums.menu.MenuCommonBizTypeEnum;
import com.kitex.mom.wms.enums.menu.ReturnReceiptActionEnum;
import com.kitex.mom.wms.enums.menu.MenuInBizTypeEnum;
import com.kitex.mom.wms.enums.menu.MenuOutBizTypeEnum;
import com.kitex.mom.wms.util.WmsStockOwnership;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.EnumSet;
import java.util.Set;
import java.util.Objects;

/**
 * 仓库业务配置在正式写入口的准入校验。
 */
@Component
@RequiredArgsConstructor
public class WmsWarehouseBusinessGuard {
	private static final List<Operation> STANDARD_OPERATIONS = List.of(
			new Operation("purchase", "采购入库", true),
			new Operation("return", "生产退料", true),
			new Operation("product", "成品入库", true),
			new Operation("sales_return", "销售退货", true),
			new Operation("other_in", "其他入库", true),
			new Operation("other_inventory", "盘点入库", true),
			new Operation("inventory", "盘点入库（兼容编码）", true),
			new Operation("allocation", "调拨接收", true),
			new Operation("produce", "生产领料", false),
			new Operation("sales", "销售出库", false),
			new Operation("other_out", "其他出库", false),
			new Operation("other_retail", "零售出库", false),
			new Operation("other_outsource", "外协出库", false),
			new Operation("other_pms", "采购退供", false),
			new Operation("other_inventory", "盘点出库", false),
			new Operation("allocation", "调拨发出", false),
			new Operation("move_site", "库内移动", false));

	private final RemoteWarehouseService remoteWarehouseService;
	private final WmsWarehouseBizService warehouseBizService;
	private final WmsWarehousePlacementGuard placements;

	/**
	 * 所有普通收发入口复用放置范围，避免只依赖移动端选择器。
	 */
	public void checkPlacements(String warehouseCode, List<WmsWarehousePlacementGuard.Placement> rows) {
		placements.check(warehouseCode, rows);
	}

	/**
	 * 手机端开关只控制新作业；原请求核对和安全取消不调用此入口。
	 */
	public void checkMobile(String warehouseCode) {
		if (!mobileEnabled(warehouseCode)) {
			throw new CheckedException("仓库[" + warehouseCode + "]未开启手机端作业，请在仓库设置中开启");
		}
	}

	public boolean mobileEnabled(String warehouseCode) {
		Integer enabled = loadWarehouse(warehouseCode).getIsMobile();
		if (enabled != null && enabled != 0 && enabled != 1) {
			throw new CheckedException("仓库手机端设置无效，请核对仓库设置");
		}
		return Integer.valueOf(1).equals(enabled);
	}

	/**
	 * 组织调拨必须显式启用，未配置历史仓库也不自动放开跨组织能力。
	 */
	public void checkOrganizationTransfer(String warehouseCode) {
		boolean selected = loadConfiguration(warehouseCode).stream()
				.anyMatch(
						row -> MenuCommonBizTypeEnum.ORGANIZATION_TRANSFER.getValue().equals(row.getBizType()));
		if (!selected) {
			throw new CheckedException("当前仓库未启用组织调拨业务");
		}
	}

	public void checkSpecialMovement(String warehouseCode) {
		boolean selected = loadConfiguration(warehouseCode).stream()
				.anyMatch(row -> MenuCommonBizTypeEnum.SPECIAL_MOVEMENT.getValue().equals(row.getBizType()));
		if (!selected) {
			throw new CheckedException("当前仓库未启用特殊收发业务");
		}
	}

	public boolean check(String warehouseCode, String billType, boolean inbound) {
		Evaluation evaluation = evaluate(warehouseCode, billType, inbound);
		if (!evaluation.allowed()) {
			throw new CheckedException("仓库[" + warehouseCode + "]未配置业务[" + billType + "]");
		}
		return true;
	}

	/**
	 * 只读评估与写入口使用同一判定；不把历史兼容当作已配置。
	 */
	public Evaluation evaluate(String warehouseCode, String billType, boolean inbound) {
		if (StrUtil.isBlank(warehouseCode) || StrUtil.isBlank(billType)) {
			throw new CheckedException("仓库编码和业务类型不能为空");
		}
		return evaluateConfigured(loadConfiguration(warehouseCode), billType, inbound);
	}

	/**
	 * 一次读取当前仓库，逐项复用正式写入口的业务准入判定。
	 */
	public List<MatrixRow> previewMatrix(String warehouseCode) {
		if (StrUtil.isBlank(warehouseCode)) {
			throw new CheckedException("仓库编码不能为空");
		}
		List<WmsWarehouseBizEntity> configurations = loadConfiguration(warehouseCode);
		return STANDARD_OPERATIONS.stream().map(operation -> {
			Evaluation result = evaluateConfigured(configurations, operation.billType(), operation.inbound());
			return new MatrixRow(operation.billType(), operation.label(), operation.inbound(),
					result.allowed(), result.configurationSource(), result.requiredOperation(), result.reason(),
					WmsStockOwnership.allowedOwnerTypes(operation.billType(), operation.inbound()),
					WmsStockOwnership.externalIdentityRequirement(operation.billType(), operation.inbound()));
		}).toList();
	}

	/**
	 * 未配置历史仓库仅兼容原能力；已经配置的仓库必须逐项启用。
	 */
	public Set<ReturnReceiptActionEnum> returnReceiptActions(String warehouseCode) {
		List<WmsWarehouseBizEntity> configurations = loadConfiguration(warehouseCode);
		if (configurations.isEmpty()) {
			return EnumSet.allOf(ReturnReceiptActionEnum.class);
		}
		Set<String> selected = configurations.stream().map(WmsWarehouseBizEntity::getBizType)
				.collect(java.util.stream.Collectors.toSet());
		var actions = EnumSet.noneOf(ReturnReceiptActionEnum.class);
		if (selected.contains(MenuInBizTypeEnum.RETURN.getValue()) &&
				ReturnReceiptActionEnum.validSelection(selected)) {
			for (ReturnReceiptActionEnum action : ReturnReceiptActionEnum.values()) {
				if (selected.contains(action.configurationValue())) {
					actions.add(action);
				}
			}
		}
		return actions;
	}

	private List<WmsWarehouseBizEntity> loadConfiguration(String warehouseCode) {
		loadWarehouse(warehouseCode);
		// 每次读取当前配置，确保草稿审核与 PDA 后续作业使用最新范围。
		List<WmsWarehouseBizEntity> configurations = warehouseBizService
				.list(Wrappers.<WmsWarehouseBizEntity>lambdaQuery()
						.eq(WmsWarehouseBizEntity::getWarehouseCode, warehouseCode)
						.eq(WmsWarehouseBizEntity::getTenantId, TenantContextHolder.getTenantId())
						.eq(WmsWarehouseBizEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()));
		if (configurations == null) {
			throw new CheckedException("读取仓库业务配置失败，禁止继续出入库");
		}
		return configurations;
	}

	/**
	 * 核验当前租户和实际工厂下的仓主档存在且已启用，供签发仓级业务授权时使用。
	 * 仓配置、移动开关及具体作业权限由调用方按各自动作检查。
	 */
	public void requireEnabledWarehouse(String warehouseCode) {
		loadWarehouse(warehouseCode);
	}

	private MdmWarehouseEntity loadWarehouse(String warehouseCode) {
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		if (tenant == null || StrUtil.isBlank(factory)
				|| Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
				|| Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())) {
			throw new CheckedException("出入库必须具有明确的租户和工厂上下文");
		}
		R<MdmWarehouseEntity> response = remoteWarehouseService.getByCode(warehouseCode);
		if (response == null || !response.isOk()) {
			throw new CheckedException("读取仓库配置失败，禁止继续出入库");
		}
		MdmWarehouseEntity warehouse = response.getData();
		if (warehouse == null || !Objects.equals(warehouseCode, warehouse.getWarehouseCode())
				|| !Boolean.TRUE.equals(warehouse.getStatus())) {
			throw new CheckedException("仓库[" + warehouseCode + "]不存在或未启用");
		}
		if (!Objects.equals(tenant, warehouse.getTenantId())) {
			throw new CheckedException("仓库[" + warehouseCode + "]不属于当前租户");
		}
		if (!Objects.equals(factory, warehouse.getFactoryCode())) {
			throw new CheckedException("仓库[" + warehouseCode + "]不属于当前工厂");
		}
		return warehouse;
	}

	private Evaluation evaluateConfigured(List<WmsWarehouseBizEntity> configurations,
	                                      String billType, boolean inbound) {
		String menu = businessMenu(billType, inbound);
		if (menu == null) {
			String source = configurations.isEmpty() ? "LEGACY_UNCONFIGURED" : "WAREHOUSE_CONFIGURATION";
			return new Evaluation(false, source, null, "业务类型没有对应的标准仓库作业");
		}
		// 历史未配置仓库继续兼容；新配置为空时由NO_BUSINESS记录明确禁止所有业务。
		if (configurations.isEmpty()) {
			return new Evaluation(true, "LEGACY_UNCONFIGURED", menu, "历史仓库未配置作业范围，沿用旧行为");
		}
		boolean allowed =
				menu != null && configurations.stream().anyMatch(item -> menu.equals(item.getBizType()));
		String subtype = WmsOtherOutboundScope.selectionFor(billType);
		boolean subtypeRestricted = false;
		if (allowed && subtype != null && configurations.stream()
				.anyMatch(item -> WmsOtherOutboundScope.MARKER.equals(item.getBizType()))) {
			allowed = configurations.stream().anyMatch(item -> subtype.equals(item.getBizType()));
			subtypeRestricted = !allowed;
		}
		String reason = allowed ? "仓库已启用该作业"
				: subtypeRestricted ? "当前仓库未启用该其他出库子类型" : "当前仓库未启用该作业";
		return new Evaluation(allowed, "WAREHOUSE_CONFIGURATION", menu, reason);
	}

	public record Evaluation(boolean allowed, String configurationSource, String requiredOperation,
	                         String reason) {
	}

	public record MatrixRow(String billType, String label, boolean inbound, boolean allowed,
	                        String configurationSource, String requiredOperation, String reason,
	                        List<String> allowedOwnerTypes, String externalIdentityRequirement) {
	}

	private record Operation(String billType, String label, boolean inbound) {
	}

	private String businessMenu(String billType, boolean inbound) {
		if ("other_inventory".equals(billType) || (inbound && "inventory".equals(billType))) {
			return MenuCommonBizTypeEnum.INVENTORY.getValue();
		}
		if ("allocation".equals(billType)) {
			return (inbound ? MenuCommonBizTypeEnum.ALLOCATION_RECEIVE : MenuCommonBizTypeEnum.ALLOCATION).getValue();
		}
		if (!inbound && "move_site".equals(billType)) {
			return MenuCommonBizTypeEnum.MOVE_SITE.getValue();
		}
		if (inbound) {
			MenuInBizTypeEnum business = MenuInBizTypeEnum.getByCode(billType);
			return business == null ? null : business.getValue();
		}
		if ("other_retail".equals(billType) || "other_outsource".equals(billType) ||
				"other_pms".equals(billType)) {
			return MenuOutBizTypeEnum.OTHER_OUT.getValue();
		}
		MenuOutBizTypeEnum business = MenuOutBizTypeEnum.getByCode(billType);
		return business == null ? null : business.getValue();
	}
}
