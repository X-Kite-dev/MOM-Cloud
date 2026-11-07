package com.kitex.mom.mes.dto;

import java.util.List;

/**
 * 原批准工程标准与独立手工或普通PLAN工单批准/接收来源；十进制原值以文本返回，避免浏览器浮点改写。
 */
public final class MesEngineeringLaborStandardDTO {
	private MesEngineeringLaborStandardDTO() {
	}

	/**
	 * 工单原工程标准视图，历史无联合包时版本身份保持空。
	 */
	public record View(
			/** 当前真实组织工单号。 */String taskCode,
			/** 原发布记录ID文本；历史缺失为null。 */String releaseId,
			/** 原工程显示版本；历史缺失为null。 */Integer versionNo,
			/** 原工程字节SHA256；历史缺失为null。 */String engineeringHash,
			/** 原路线全部节点的标准观察，不借今日主数据回填。 */List<Node> nodes,
			/** 真实独立手工来源已核验；仅表示可进入申请工作台，不表示节点可变更。 */boolean manualLaborChangeSupported) {
	}

	/**
	 * 稳定节点标准及来源；重复主工序各节点独立展示。
	 */
	public record Node(
			/** 原稳定节点RN-id；旧路线可能缺失。 */String routeNodeCode,
			/** 原主工序编码。 */String processCode,
			/** FROZEN_SOURCE或UNKNOWN_HISTORICAL。 */String sourceState,
			/** 原工序标准来源记录ID文本；未知为null。 */String sourceId,
			/** 原标准来源字节SHA256；未知为null。 */String sourceFingerprint,
			/** 原来源业务更新时间文本；未知为null。 */String sourceUpdatedAt,
			/** 原来源更新账号；未知为null。 */String sourceUpdatedBy,
			/** 原活动基本批量及单位，不以1/默认单位填补。 */Basis basis,
			/** 准备/人工加工/机器加工/拆卸四类原标准。 */List<Measure> standards,
			/** 本节点工时批准层；手工批准或PLAN的APPLIED，无批准时为null，原标准保持。 */Approved approved,
			/** 本节点接收时永久冻结的实际采用来源，历史无账保持空。 */List<Adoption> adoptions) {
	}

	/**
	 * 仅本节点标准工时批准层，独立于SOP层，不能表示整个工单已换工程版本。
	 */
	public record Approved(
			/** 不可变批准层主键文本。 */String changeId,
			/** 标准来源正式工程发布主键文本。 */String releaseId,
			/** 标准来源完整工程包SHA256。 */String contentHash,
			/** 异人批准实际时刻文本；PLAN为APPLIED决策提交时刻。 */String approvedAt,
			/** 申请业务依据。 */String reason,
			/** 来源标准sourceSnapshot的SHA256。 */String sourceFingerprint,
			/** 活动基本批量与原单位。 */Basis basis,
			/** 四类标准原值及原单位。 */List<Measure> standards) {
	}

	/**
	 * 接收时采用来源摘要；已接收后始终按同一批准层主键与hash回读。
	 */
	public record Adoption(
			/** 原接收采用账主键文本。 */String id,
			/** 原实际派工主键文本。 */String arrangeId,
			/** 采用的批准层主键文本；采用原工程时为null。 */String changeId,
			/** 实际采用标准原件SHA256；历史未知为null。 */String sourceFingerprint,
			/** 服务器冻结实际采用来源的接收时刻。 */String receivedAt) {
	}

	/**
	 * 原标准批量依据，数量使用十进制文本保留精度。
	 */
	public record Basis(
			/** 原活动基本批量；缺失为null。 */String quantity,
			/** 原批量单位；缺失为null。 */String unit,
			/** DECLARED明确有效，UNKNOWN未知。 */String state) {
	}

	/**
	 * 单类原标准；不合并成人工实际时长、机器实际时长或劳动成本。
	 */
	public record Measure(
			/** SETUP准备、LABOR人工加工、MACHINE机器加工、DISASSEMBLY拆卸。 */String kind,
			/** 原标准十进制文本；缺失为null。 */String value,
			/** 原标准时间单位；缺失为null，不默认分钟。 */String unit,
			/** DECLARED来源及依据齐全，UNKNOWN存在缺证。 */String state) {
	}
}
