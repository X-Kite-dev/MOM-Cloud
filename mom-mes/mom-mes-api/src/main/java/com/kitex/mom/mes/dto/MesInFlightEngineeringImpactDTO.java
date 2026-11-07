package com.kitex.mom.mes.dto;

import java.time.Instant;
import java.util.List;

/**
 * 在制工程影响的只读合同。正文均由服务端原件生成，客户端不能上传证据或工程包；
 * 缺少跨域全量证明时仍能阅读差异，但任何返回结果都没有执行效力。
 */
public final class MesInFlightEngineeringImpactDTO {
	private MesInFlightEngineeringImpactDTO() {
	}

	/**
	 * 节点执行观察：完成、进行中、未开工、缺证；不代表可迁移资格。
	 */
	public enum ExecutionState {COMPLETED, IN_PROGRESS, NOT_STARTED, MISSING_EVIDENCE}

	/**
	 * 原件差异类别；材料保留逐料policy，BOM与全图另有完整投影。
	 */
	public enum Category {MATERIAL, GRAPH, LABOR_STANDARD, BOM, OTHER_ENGINEERING}

	/**
	 * 明确由所属域提供的证明，不能使用MES空投料或页面JSON替代。
	 */
	public enum ProviderType {WMS_RESERVATION_AND_PHYSICAL, PLAN_RESOURCE_ALLOCATION, MES_EXECUTION_WATERMARK}

	/**
	 * 当前未接通权威提供方，不能声明全量、无占用或有效版本栅栏。
	 */
	public enum EvidenceStatus {REQUIRED_NOT_CONNECTED}

	/**
	 * 原发布版本对。ID为十进制文本，hash指向保存的原始UTF-8字节。
	 */
	public record PackageRef(
			/** 正式工程发布记录ID；不是客户端临时包身份。 */String releaseId,
			/** 原件SHA256。 */String contentHash,
			/** 路线ID文本。 */String routeId,
			/** BOM ID文本。 */String bomId,
			/** 原发布显示版本号。 */Integer versionNo) {
	}

	/**
	 * 字段级精确差异；JSON字符串由服务器解析，无浮点运算，不在浏览器反序列化。
	 */
	public record FieldChange(
			/** JSON Pointer路径，数组以原件下标保留重复项。 */String path,
			/** 原包是否存在字段；与存在且null区分。 */boolean beforePresent,
			/** 目标包是否存在字段。 */boolean afterPresent,
			/** 原值JSON；缺失时为null，数量保留十进制精度。 */String beforeJson,
			/** 目标值JSON；缺失时为null。 */String afterJson) {
	}

	/**
	 * 同类完整投影及字段差异；原件hash另列，不用派生JSON代替原始字节。
	 */
	public record Difference(
			/** 材料、图、标准工时、BOM或其他工程。 */Category category,
			/** 精确结构比较是否发现变化。 */boolean changed,
			/** 原完整投影JSON；节点/标准缺失则为null。 */String beforeJson,
			/** 目标完整投影JSON。 */String afterJson,
			/** 全部字段差异；禁止截断后冒充完整。 */List<FieldChange> fields) {
	}

	/**
	 * 本地执行原记录观察，只负责说明现状，不能充当跨域生效水位。
	 */
	public record ExecutionFact(
			/** PROCESS/ARRANGE/REPORT。 */String kind,
			/** 实际记录ID文本。 */String id,
			/** 实际工序/派工/报工请求号，可为空。 */String code,
			/** 原始状态，不将已冲销报工删除。 */String status,
			/** 含数量、时间、原节点身份的只读JSON；数字不转浮点。 */String snapshotJson) {
	}

	/**
	 * 按稳定节点对比，不以同名主工序或路线序号归并。
	 */
	public record NodeImpact(
			/** RN-id稳定节点；目标新增/删除同样保留原身份。 */String routeNodeCode,
			/** 原工序编码，删除或新增侧允许为空。 */String beforeProcessCode,
			/** 目标工序编码。 */String afterProcessCode,
			/** 本地执行观察分类；新增节点没有MES出生事实时为缺证。 */ExecutionState executionState,
			/** 分类依据及数据缺口，不表达可执行承诺。 */List<String> reasons,
			/** 全部实际工序、派工及报工观察，含原已完成事实。 */List<ExecutionFact> facts,
			/** 材料含逐料policy、工序图、冻结标准工时三组完整差异。 */List<Difference> differences) {
	}

	/**
	 * 所需证据的组织、原包、目标包和全节点范围；不接受客户端自行缩小范围。
	 */
	public record EvidenceScope(
			/** 当前认证租户文本。 */String tenantId,
			/** 当前实际授权工厂。 */String factoryCode,
			/** 当前MES工单原ID文本。 */String taskId,
			/** 当前MES工单号。 */String taskCode,
			/** 原冻结版本对。 */PackageRef source,
			/** 目标正式版本对。 */PackageRef target,
			/** 源目标稳定节点并集。 */List<String> routeNodeCodes,
			/** 完整派生材料范围的SHA256；缺证为null，不能当WMS占用证据。 */String materialScopeFingerprint) {
	}

	/**
	 * 待提供方合同；原件、时刻、有效期均未知，不生成假的空结果或权限名。
	 */
	public record ProviderRequirement(
			/** 负责事实的所属域。 */ProviderType provider,
			/** 当前接入状态，始终待证。 */EvidenceStatus status,
			/** 需要证明的完整范围。 */EvidenceScope scope,
			/** 必须完整覆盖的事实/资源类型。 */List<String> requiredCoverage,
			/** 全量性及负向证据要求。 */String completenessRequirement,
			/** 所属域真实原件ID，未取得为null。 */String originalId,
			/** 所属域原件字节SHA256，未取得为null。 */String originalHash,
			/** 所属域观测UTC时刻，未取得为null。 */Instant observedAt,
			/** 所属域证明失效UTC时刻，未取得为null。 */Instant validUntil,
			/** 全量版本栅栏/执行水位，未取得为null。 */String versionFence,
			/** 所属域实名权限核验结果，未接入为false。 */boolean permissionVerified,
			/** 实际所需权限边界，不虚构已安装权限码。 */String permissionRequirement) {
	}

	/**
	 * 本次服务器只读观察；没有任何材料、工时或图生效命令。
	 */
	public record Preview(
			/** 固定MES_INFLIGHT_ENGINEERING_IMPACT_V1。 */String schemaVersion,
			/** 始终false，预览不可执行。 */boolean executable,
			/** 当前工单号。 */String taskCode,
			/** 本地只读观察完成UTC时刻，不是跨域有效期。 */Instant observedAt,
			/** 原冻结版本身份。 */PackageRef source,
			/** 目标正式版本身份。 */PackageRef target,
			/** 按稳定节点生成的完整差异。 */List<NodeImpact> nodes,
			/** 全BOM、全图及其他工程差异，防止节点外内容被遗漏。 */List<Difference> packageDifferences,
			/** 无法绑定稳定节点的真实原工序/派工/报工，不能静默丢弃。 */List<ExecutionFact> unboundFacts,
			/** 必须从权威域接入的typed提供方清单。 */List<ProviderRequirement> providerRequirements,
			/** 真实执行缺口，任何一项未实现都不允许生效。 */List<String> blockers,
			/** 追加的双侧完整材料范围，仅供核对；旧服务无此字段时由页面明确提示。 */
			MesEngineeringMaterialScopeDTO.Scope materialScope) {
	}
}
