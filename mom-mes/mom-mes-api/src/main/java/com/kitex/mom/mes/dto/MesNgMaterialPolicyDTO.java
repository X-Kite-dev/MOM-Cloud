package com.kitex.mom.mes.dto;

import java.util.List;

/**
 * 原NG逐料账及独立恢复；ID、数量及原文均为文本。
 */
public final class MesNgMaterialPolicyDTO {
	private MesNgMaterialPolicyDTO() {
	}

	/**
	 * 原耗用份额与独立恢复引用。
	 */
	public record Share(
			/** 原耗用分配行身份；字符串雪花ID。 */
			String allocationId,
			/** 来源类型：FEED原投料，STOCK原库存倒冲。 */
			String kind,
			/** 原投料或库存身份，组织范围沿原账。 */
			String sourceId,
			/** 原真实库存OUT身份；FEED为空。 */
			String sourceFlowId,
			/** 原冻结需求物料编码。 */
			String requirementMaterialCode,
			/** 原需求计量单位。 */
			String requirementUnit,
			/** 实际来源物料编码。 */
			String materialCode,
			/** 实际来源计量单位。 */
			String materialUnit,
			/** 原实际正量份额，单位为materialUnit，精确十进制文本。 */
			String quantity,
			/** 原需求当量，单位为requirementUnit，精确十进制文本。 */
			String requirementQuantity,
			/** 实际数量除以需求当量的原替代比率。 */
			String ratio,
			/** 是否使用原BOM允许的替代物料。 */
			boolean substitute,
			/** 原耗用前余额；FEED为已用量，STOCK为库存量，单位为materialUnit。 */
			String beforeQuantity,
			/** 原耗用后余额，与beforeQuantity使用同一单位。 */
			String afterQuantity,
			/** 原来源身份及真实回执的不可变JSON原文。 */
			String sourceSnapshot,
			/** 该原份额的唯一恢复行身份；尚未恢复为空。 */
			String restoreId) {
	}

	/**
	 * 真实NG的完整原耗用读模型。
	 */
	public record Operation(
			/** 原NG逐料耗用账身份。 */
			String operationId,
			/** 真实原NG报告身份；恢复不修改该报告。 */
			String sourceUnqualifiedReportId,
			/** 原稳定请求号，不因传输未知而换号。 */
			String requestId,
			/** 实际认证租户身份，字符串传输。 */
			String tenantId,
			/** 经实际权限校验的工厂编码。 */
			String factoryCode,
			/** 原实际操作人员身份，非客户端自报授权。 */
			String actorId,
			/** 原生产工单编码。 */
			String taskCode,
			/** 原派工编码。 */
			String arrangeCode,
			/** 不可变证据协议版本。 */
			String evidenceVersion,
			/** 完整份额及来源证据JSON原文。 */
			String evidenceSnapshot,
			/** 证据版本与原文绑定的SHA-256。 */
			String evidenceFingerprint,
			/** 完整原正量份额集合；恢复后仍保留原值。 */
			List<Share> shares,
			/** 完整独立恢复回执；未恢复为空。 */
			Receipt restoration) {
	}

	/**
	 * 原号核对结果；另号已恢复不冒充本号成功。
	 */
	public record Resolution(
			/** 原稳定请求号，不因传输未知而换号。 */
			String requestId,
			/** 核对状态：APPLIED已提交、ABSENT本号未提交；Resolution另含NOT_APPLIED_ALREADY_RESTORED。 */
			String state,
			/** 实际认证租户身份，字符串传输。 */
			String tenantId,
			/** 经实际权限校验的工厂编码。 */
			String factoryCode,
			/** 原实际操作人员身份，非客户端自报授权。 */
			String actorId,
			/** 恢复命令协议版本。 */
			String commandVersion,
			/** 原号对应的完整命令原文。 */
			String commandSnapshot,
			/** 命令版本与原文绑定的SHA-256。 */
			String commandFingerprint,
			/** 本号或另号实际已提交的恢复回执；无结果为空。 */
			Receipt applied) {
	}

	/**
	 * 恢复原号的永久回执或明确未提交状态。
	 */
	public record Receipt(
			/** 原稳定请求号，不因传输未知而换号。 */
			String requestId,
			/** 核对状态：APPLIED已提交、ABSENT本号未提交；Resolution另含NOT_APPLIED_ALREADY_RESTORED。 */
			String state,
			/** 独立全份额恢复账身份；ABSENT时为空。 */
			String reversalId,
			/** 原NG逐料耗用账身份。 */
			String operationId,
			/** 实际认证租户身份，字符串传输。 */
			String tenantId,
			/** 经实际权限校验的工厂编码。 */
			String factoryCode,
			/** 原实际操作人员身份，非客户端自报授权。 */
			String actorId,
			/** 恢复命令协议版本。 */
			String commandVersion,
			/** 原号对应的完整命令原文。 */
			String commandSnapshot,
			/** 命令版本与原文绑定的SHA-256。 */
			String commandFingerprint,
			/** 不可变证据协议版本。 */
			String evidenceVersion,
			/** 完整份额及来源证据JSON原文。 */
			String evidenceSnapshot,
			/** 证据版本与原文绑定的SHA-256。 */
			String evidenceFingerprint) {
	}
}
