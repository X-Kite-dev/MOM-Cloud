package com.kitex.mom.mdm.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.mdm.agent.service.MdmAgentActualScopeAccess;
import com.kitex.mom.mdm.dto.MdmRouteMaterialConsumptionDTO;
import com.kitex.mom.mdm.dto.MdmRouteMaterialConsumptionDTO.*;
import com.kitex.mom.mdm.mapper.*;
import com.kitex.mom.mdm.support.MdmRouteNodeResolver;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Actual node, raw nullable settings and original material rows in one read snapshot.
 */
@Service
@RequiredArgsConstructor
public class MdmRouteMaterialConsumptionReader {
	private final MdmRouteNodeResolver nodes;
	private final MdmProcessProcedureSettingMapper settings;
	private final MdmRouteMaterialConsumptionMapper rows;
	private final MdmAgentActualScopeAccess access;
	private final PermissionService permissions;
	private final MdmBomService bom;

	/**
	 * 在同一可重复读快照核对节点、原路线设置和实际BOM；未知值保留null，矛盾配置或截断风险拒绝。
	 *
	 * @param nodeId 当前实际工厂已保存节点ID字符串
	 * @return 保留原数量精度和三态设置的只读配置
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public View inspect(String nodeId) {
		require(
				permissions.hasPermission("mdm_mdmProcessRoute_view", "mdm_mdmProcessRoute_edit"),
				"没有工艺路线读取权限");
		String factory = FactoryContextHolder.getFactoryCode();
		access.require(factory, "read");
		require(nodeId != null && nodeId.matches("[1-9][0-9]{0,18}"), "请选择已保存的路线节点");
		Long id;
		try {
			id = Long.valueOf(nodeId);
		} catch (NumberFormatException e) {
			throw new CheckedException("路线节点ID无效");
		}
		var node = nodes.byId(id, false);
		Long tenant = TenantContextHolder.getTenantId();
		var routes = rows.route(tenant, factory, node.getRouteCode());
		require(routes.size() == 1, "工艺路线归属不唯一，请刷新核对");
		var route = routes.get(0);
		var source =
				settings.nullableByNode(
						tenant, factory, node.getRouteCode(), node.getRouteNodeCode());
		require(source.size() <= 1, "节点执行设置不唯一，请先修复重复配置");
		var setting = source.isEmpty() ? null : source.get(0);
		require(
				setting == null || Objects.equals(setting.getProcessCode(), node.getProcessCode()),
				"执行设置主工序与节点不一致");
		var materials =
				rows.materials(tenant, factory, node.getRouteCode(), node.getRouteNodeCode());
		require(materials.size() <= 100, "节点绑定物料超过100项，不能截断后保存");
		String schema = setting == null ? null : setting.getMaterialConsumptionSchema();
		Boolean back = setting == null ? null : setting.getIsBackFlush();
		require(schema == null || "MATERIAL_CONSUMPTION_V2".equals(schema), "物料消耗配置版本未知");
		boolean per = schema != null;
		require(!per || back == null, "逐料方式与统一倒冲标记冲突，请先修复原配置");
		require(!per || !materials.isEmpty(), "逐料方式必须具有明确的物料需求");
		var gaps = new ArrayList<String>();
		if (!per && back == null) {
			gaps.add("UNIFIED_MODE_UNSET");
		}
		var bound = new ArrayList<Material>();
		var codes = new HashSet<String>();
		for (var row : materials) {
			require(Objects.equals(row.getProcessCode(), node.getProcessCode()), "绑定物料主工序与节点不一致");
			require(
					nonblank(row.getMaterialCode()) && codes.add(row.getMaterialCode()),
					"节点绑定含空白或重复物料编码");
			require(
					per
							? Set.of("FEED", "BACKFLUSH")
							.contains(Objects.toString(row.getConsumptionMode(), ""))
							: row.getConsumptionMode() == null,
					"物料行消耗方式与节点策略不一致");
			if (row.getQuantity() == null
					|| row.getQuantity().signum() <= 0
					|| !nonblank(row.getUnit())
					|| row.getStep() == null
					|| row.getStep() <= 0) {
				gaps.add("MATERIAL_VALUE_INCOMPLETE:" + row.getMaterialCode());
			}
			bound.add(
					new Material(
							text(row.getId()),
							row.getMaterialCode(),
							row.getMaterialName(),
							row.getMaterialSpec(),
							row.getUnit(),
							decimal(row.getQuantity()),
							text(row.getStep()),
							row.getConsumptionMode()));
		}
		var available = new ArrayList<Material>();
		var children =
				!nonblank(route.getProductCode())
						? null
						: bom.getBomChild(route.getProductCode(), route.getBomVersion());
		require(children == null || children.size() <= 2000, "可选BOM物料超过2000项，请先拆分工程配置");
		if (children != null) {
			for (var row : children) {
				require(
						Objects.equals(row.getTenantId(), tenant)
								&& Objects.equals(row.getFactoryCode(), factory),
						"BOM物料不属于当前实际租户工厂");
				require(nonblank(row.getMaterialCode()) && row.getId() != null, "BOM物料身份不完整");
				available.add(
						new Material(
								text(row.getId()),
								row.getMaterialCode(),
								row.getMaterialName(),
								row.getMaterialSpec(),
								row.getUnit(),
								decimal(row.getQuantity()),
								"1",
								null));
			}
		}
		return new View(
				MdmRouteMaterialConsumptionDTO.SCHEMA,
				text(tenant),
				factory,
				new Node(
						text(node.getId()),
						text(route.getId()),
						route.getRouteCode(),
						route.getRouteName(),
						node.getRouteNodeCode(),
						node.getProcessCode(),
						node.getProcessName(),
						route.getProductCode(),
						route.getProductName(),
						route.getBomVersion()),
				per ? "PER_MATERIAL" : back == null ? "INHERIT" : back ? "BACKFLUSH" : "FEED",
				schema,
				back,
				setting == null
						? null
						: new Setting(
						text(setting.getId()),
						setting.getIsMatWeightConvert(),
						decimal(setting.getMatWeightConvert()),
						setting.getIsAutomate(),
						setting.getTimeCheck(),
						decimal(setting.getTimeVal()),
						decimal(setting.getDefaultReportNum())),
				bound,
				available,
				children == null || children.isEmpty() ? "NOT_CONFIGURED" : "AVAILABLE",
				gaps);
	}

	/**
	 * 检查原配置文本是否明确存在，不把空文本当作有效配置。
	 */
	private static boolean nonblank(String s) {
		return s != null && !s.isBlank();
	}

	/**
	 * 保留原ID或整数的十进制文本，缺失保持null，不经浮点转换。
	 */
	private static String text(Object n) {
		return n == null ? null : n.toString();
	}

	/**
	 * 保留原十进制数量的精确文本并去尾零，未配置保持null。
	 */
	private static String decimal(BigDecimal n) {
		return n == null ? null : n.stripTrailingZeros().toPlainString();
	}

	/**
	 * 业务条件不满足时按原异常封装拒绝，不降级为空配置。
	 */
	private static void require(boolean ok, String message) {
		if (!ok) {
			throw new CheckedException(message);
		}
	}
}
