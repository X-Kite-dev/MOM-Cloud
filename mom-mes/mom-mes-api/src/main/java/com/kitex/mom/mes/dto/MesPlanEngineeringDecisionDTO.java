package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * PLAN关联工单节点标准工时决策合同；主键和十进制原值按文本，不接收客户端目标包。
 */
public final class MesPlanEngineeringDecisionDTO {

	private MesPlanEngineeringDecisionDTO() {
	}

	/**
	 * 异人批准同一原准备的目标节点工时，原号与两个观察摘要不变。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Apply(
			/**
			 * 原PEP准备号，沿同一原号永久恢复。
			 */
			String requestId,
			/**
			 * PLAN不可变权威凭据SHA256。
			 */
			String expectedAuthorityFingerprint,
			/**
			 * 已展示的完整MES节点范围SHA256。
			 */
			String expectedMesFingerprint,
			/**
			 * 实际异人复核依据，非空且最多1000字符。
			 */
			String reason) {
	}

	/**
	 * PLAN发起的原准备永久撤销；与采用共用同一个MES决策键。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Cancel(
			/**
			 * 原PEP准备号，不生成第二请求号。
			 */
			String requestId,
			/**
			 * 原准备权威凭据SHA256。
			 */
			String expectedAuthorityFingerprint,
			/**
			 * 原撤销依据，非空且最多1000字符。
			 */
			String reason) {
	}

	/**
	 * 只读完整预核；不产生批准、接收或PLAN释放。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Preview(
			/**
			 * 固定MES_PLAN_ENGINEERING_PREVIEW_V1。
			 */
			String schemaVersion,
			/**
			 * 原PEP准备号。
			 */
			String requestId,
			/**
			 * 原PLAN权威凭据SHA256。
			 */
			String authorityFingerprint,
			/**
			 * 本次完整MES节点范围SHA256。
			 */
			String mesFingerprint,
			/**
			 * 原实际工序任务ID文本。
			 */
			String processId,
			/**
			 * 原稳定节点RN-id。
			 */
			String routeNodeCode,
			/**
			 * 原主工序编码。
			 */
			String processCode,
			/**
			 * 原标准和拟采用标准的精确原值/单位对照。
			 */
			MesManualLaborChangeDTO.Comparison comparison) {
	}

	/**
	 * MES不可变终态；APPLIED表示节点工时批准层已生效，实际接收另存采用账。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Receipt(
			/**
			 * 固定MES_PLAN_ENGINEERING_DECISION_V1。
			 */
			String schemaVersion,
			/**
			 * 永久MES决策主键文本。
			 */
			String id,
			/**
			 * 真实租户主键文本。
			 */
			String tenantId,
			/**
			 * 真实授权工厂编码。
			 */
			String factoryCode,
			/**
			 * 原PLAN准备父账主键文本。
			 */
			String preparationId,
			/**
			 * 原PEP永久准备号。
			 */
			String requestId,
			/**
			 * 原PLAN权威凭据SHA256。
			 */
			String authorityFingerprint,
			/**
			 * 原MES工单主键文本。
			 */
			String taskId,
			/**
			 * 原MES工单号。
			 */
			String taskCode,
			/**
			 * 原产品编码。
			 */
			String productCode,
			/**
			 * 原冻结工程发布ID文本。
			 */
			String sourceReleaseId,
			/**
			 * 原冻结联合包SHA256。
			 */
			String sourceContentHash,
			/**
			 * 标准来源正式工程发布ID文本。
			 */
			String targetReleaseId,
			/**
			 * 标准来源正式联合包SHA256。
			 */
			String targetContentHash,
			/**
			 * APPLIED工时批准层生效或CANCELLED永久撤销，两者互斥。
			 */
			String decision,
			/**
			 * 实际决策人员主键文本。
			 */
			String actorId,
			/**
			 * 实际决策当地业务时间，ISO文本保留九位小数。
			 */
			String decidedAt,
			/**
			 * 原决策依据，不被重放或PLAN确认覆盖。
			 */
			String reason,
			/**
			 * APPLIED实际目标工序任务ID；CANCELLED为空。
			 */
			String processId,
			/**
			 * APPLIED稳定节点RN-id；CANCELLED为空。
			 */
			String routeNodeCode,
			/**
			 * APPLIED主工序编码；CANCELLED为空。
			 */
			String processCode,
			/**
			 * APPLIED完整MES范围原文SHA256；CANCELLED为空。
			 */
			String sourceFingerprint,
			/**
			 * APPLIED标准工时封套原文SHA256；CANCELLED为空。
			 */
			String laborFingerprint,
			/**
			 * 原决策规范命令UTF8 SHA256。
			 */
			String commandFingerprint,
			/**
			 * 除本字段外固定规范回执正文的UTF8 SHA256。
			 */
			String receiptFingerprint) {
	}

	/**
	 * 本地MES永久事实与PLAN确认分别表达，远端失败不抹去已提交MES成功。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Outcome(
			/**
			 * MES原永久决策回执。
			 */
			Receipt receipt,
			/**
			 * PLAN已按原号确认完全相同MES终态；false仍须核对。
			 */
			boolean planAcknowledged,
			/**
			 * PLAN确认结果或需继续核对的简短说明。
			 */
			String planMessage) {
	}
}
