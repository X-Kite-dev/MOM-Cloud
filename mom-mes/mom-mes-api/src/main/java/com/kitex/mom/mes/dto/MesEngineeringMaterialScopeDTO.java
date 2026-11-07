package com.kitex.mom.mes.dto;

import java.util.List;

/**
 * 已下达工程与目标正式工程的材料范围。全部取自服务器原件，仅用于核对及协作，
 * 不代表WMS完整占用证明、替代料使用资格或工程换版批准。
 */
public final class MesEngineeringMaterialScopeDTO {

	private MesEngineeringMaterialScopeDTO() {
	}

	/**
	 * 原件中的替代声明；禁用、日期外及重复声明均保留，不按今日资格过滤。
	 */
	public record Substitute(
			/** STRUCTURED为substitutePolicy，LEGACY为replaceCodes；两者不能互相冒充。 */String origin,
			/** 本条声明在原行内的位置；结构化策略正文为二次JSON中的下标。 */String path,
			/** 声明的替代料编码，保留所有侧/路径引用。 */String materialCode,
			/** 原名称；兼容编码来源没有名称时为null。 */String materialName,
			/** 原替代料单位；未知时为null。 */String unit,
			/** 满足1单位主料需求所需替代量的原JSON；未知为null，不默认1。 */String ratioJson,
			/** 原启用值JSON，缺失为null，不推断可用。 */String enabledJson,
			/** 原生效日期文本；未声明为null。 */String effectiveStart,
			/** 原失效日期文本；未声明为null。 */String effectiveEnd,
			/** 完整声明JSON，保留优先级、认证及客户/供应商范围；兼容来源是原编码JSON。 */String snapshotJson) {
	}

	/**
	 * 一条真实BOM路径或工序配置；同物料不同路径不能合并或取首条。
	 */
	public record Row(
			/** SOURCE原冻结工程或TARGET目标正式工程。 */String side,
			/** BOM为BOM明细，NODE为工序逐料配置。 */String kind,
			/** 原工程包中的精确JSON Pointer；数组次序保持原件次序。 */String path,
			/** 原明细ID文本，不能作为跨版本的自动匹配依据。 */String sourceId,
			/** 所属BOM ID；工序配置没有该身份时为null。 */String bomId,
			/** 明确引用的子BOM ID，无子版本为null。 */String subBomId,
			/** 稳定节点RN；BOM未声明节点时为null。 */String routeNodeCode,
			/** 原主工序编码；BOM未声明时为null。 */String processCode,
			/** 原主料/节点物料编码。 */String materialCode,
			/** 原物料名称，未声明时为null。 */String materialName,
			/** 原行数量JSON，与unit配套；不乘工单量、不汇总或计算损耗。 */String quantityJson,
			/** 原行数量单位。 */String unit,
			/** 全部结构化及兼容替代声明；不代表实际可使用集合。 */List<Substitute> substitutes,
			/** 当前原行完整JSON；BOM只移除children，子层各自保留路径，避免重复嵌套膨胀。 */String snapshotJson) {
	}

	/**
	 * 完整范围或明确缺证；缺证时不返回部分行、编码并集或假的完整摘要。
	 */
	public record Scope(
			/** COMPLETE或UNAVAILABLE，仅表示材料原件的提取完整性。 */String status,
			/** 缺证原因；完整时为空列表。 */List<String> issues,
			/** snapshotJson原始UTF-8字节SHA256；缺证为null，不是WMS证据hash。 */String fingerprint,
			/** MES_ENGINEERING_MATERIAL_SCOPE_V1规范原文，含组织/工单/总需求、版本对及两侧完整材料原件。 */
			String snapshotJson,
			/** 两侧全部BOM路径和稳定节点用料，保持重复项。 */List<Row> rows,
			/** 产品、子BOM产品、主料、所有声明替代料及节点料编码的保守排序并集。 */List<String> materialCodes) {
	}
}
