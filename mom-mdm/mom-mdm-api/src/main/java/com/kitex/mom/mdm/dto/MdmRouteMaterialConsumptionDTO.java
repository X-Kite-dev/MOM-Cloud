package com.kitex.mom.mdm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 当前已授权实际工厂节点的原物料配置投影，保留旧值及数据库null三态。
 */
public final class MdmRouteMaterialConsumptionDTO {
	private MdmRouteMaterialConsumptionDTO() {
	}

	/**
	 * 当前不可变协议版本；注释和格式整理不改变原序列化值。
	 */
	public static final String SCHEMA = "MDM_ROUTE_MATERIAL_CONSUMPTION@1";

	/**
	 * 当前合同范围内的稳定路线节点信息。
	 *
	 * @param id            当前实际工厂对象ID，按十进制字符串传输。
	 * @param routeId       所属实际工厂工艺路线ID字符串。
	 * @param routeCode     所属实际工厂工艺路线编码。
	 * @param routeName     工艺路线名称，可空。
	 * @param routeNodeCode 原冻结路线的稳定节点编码，不以主工序编码代替。
	 * @param processCode   稳定节点对应的主工序编码。
	 * @param processName   主工序名称快照，可空。
	 * @param productCode   原工程或当前路线配置的产品编码。
	 * @param productName   产品名称，可空。
	 * @param bomVersion    当前路线配置的BOM版本，未配置时可空。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Node(
			String id,
			String routeId,
			String routeCode,
			String routeName,
			String routeNodeCode,
			String processCode,
			String processName,
			String productCode,
			String productName,
			String bomVersion) {
	}

	/**
	 * 原执行设置投影，数据库空值不补默认值。
	 *
	 * @param id                 当前实际工厂对象ID，按十进制字符串传输。
	 * @param isMatWeightConvert 原设置是否启用物料重量换算；保留数据库null三态。
	 * @param matWeightConvert   产料每件单位重量的十进制字符串，沿原重量单位，未设置为null。
	 * @param isAutomate         原设置是否为通过式工序；保留数据库null三态。
	 * @param timeCheck          原设置是否校验转序时间；保留数据库null三态。
	 * @param timeVal            原转序时间要求的十进制分钟字符串，未设置为null。
	 * @param defaultReportNum   默认报工数量或重量的十进制字符串，按是否重量报工解释，未设置为null。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Setting(
			String id,
			Boolean isMatWeightConvert,
			String matWeightConvert,
			Boolean isAutomate,
			Boolean timeCheck,
			String timeVal,
			String defaultReportNum) {
	}

	/**
	 * 物料原行或BOM候选，数值使用精确文本。
	 *
	 * @param id              当前实际工厂对象ID，按十进制字符串传输。
	 * @param materialCode    当前实际工厂物料编码。
	 * @param materialName    物料名称，可空。
	 * @param materialSpec    物料规格，可空。
	 * @param unit            原绑定物料数量单位，可空时由缺口提示。
	 * @param quantity        原物料需求数量十进制字符串，单位取unit，不转浮点。
	 * @param step            物料消耗顺序的整数文本，未配置可空。
	 * @param consumptionMode 逐料方式：FEED投料、BACKFLUSH倒冲；统一策略时必须为null。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Material(
			String id,
			String materialCode,
			String materialName,
			String materialSpec,
			String unit,
			String quantity,
			String step,
			String consumptionMode) {
	}

	/**
	 * 已授权范围的查询结果，未知与不可用来源保留原意。
	 *
	 * @param schemaVersion             不可变协议版本；用于解释原件而非重新计算历史。
	 * @param tenantId                  经认证的实际租户ID，按十进制字符串传输。
	 * @param factoryCode               当前已授权实际工厂编码，与原来源一致。
	 * @param node                      当前实际工厂已保存的稳定路线节点。
	 * @param consumptionPolicy         节点消耗策略：INHERIT未设置、FEED统一投料、BACKFLUSH统一倒冲、PER_MATERIAL逐料。
	 * @param materialConsumptionSchema 逐料配置时为MATERIAL_CONSUMPTION_V2，旧统一配置为null。
	 * @param isBackFlush               旧统一倒冲三态：true倒冲、false投料、null未设置；V2必须为null。
	 * @param setting                   当前数据库原执行设置，未配置为null；不补造布尔默认值。
	 * @param materials                 当前稳定节点已绑定的原物料行，保留原ID和精确数值。
	 * @param availableMaterials        当前路线产品与BOM版本下的可选物料，不替代已绑定原行。
	 * @param bomAvailability           BOM可选来源状态：AVAILABLE可读取、NOT_CONFIGURED未配置。
	 * @param dataGaps                  未核实来源或覆盖限制的稳定代码列表。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record View(
			String schemaVersion,
			String tenantId,
			String factoryCode,
			Node node,
			String consumptionPolicy,
			String materialConsumptionSchema,
			Boolean isBackFlush,
			Setting setting,
			List<Material> materials,
			List<Material> availableMaterials,
			String bomAvailability,
			List<String> dataGaps) {
		public View {
			materials = List.copyOf(materials);
			availableMaterials = List.copyOf(availableMaterials);
			dataGaps = List.copyOf(dataGaps);
		}
	}
}
