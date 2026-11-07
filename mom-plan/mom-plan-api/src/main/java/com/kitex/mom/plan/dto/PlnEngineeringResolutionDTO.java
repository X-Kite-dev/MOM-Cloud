package com.kitex.mom.plan.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * PLAN原准备的权威身份与MES永久终态索引。证明保持原工单身份，不代表APS已重排或整包换版。
 */
public final class PlnEngineeringResolutionDTO {

	private PlnEngineeringResolutionDTO() {
	}

	/**
	 * 完整原号明细才能出具凭据；authority摘要忽略动态终结状态，原准备字段保持不变。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Certificate(
			/** 原准备及当前实际终态，必须是精确原号回读。 */
			PlnEngineeringPreparationDTO.Receipt preparation,
			/** 原MES工单主键文本，不按同编码猜测新工单。 */
			String taskId,
			/** 原准备观察的产品编码。 */
			String productCode,
			/** 原准备观察的MES完整分配摘要，包含当时数量。 */
			String mappingFingerprint,
			/** 固定规范authority正文UTF8 SHA256。 */
			String authorityFingerprint) {
	}

	/**
	 * PLAN确认的实际MES永久决策；APPLIED仅表示节点工时批准层，不表示已发生接收。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Resolution(
			/** PLAN永久终结索引主键文本。 */
			String id,
			/** APPLIED工时批准已生效，CANCELLED原准备已撤销。 */
			String decision,
			/** MES原永久决策主键文本。 */
			String mesDecisionId,
			/** MES原规范回执UTF8 SHA256。 */
			String mesDecisionFingerprint,
			/** MES实际决策人员主键文本。 */
			String actorId,
			/** MES实际决策当地业务时间，保留数据库精度。 */
			String decidedAt,
			/** MES原决策依据，不被PLAN确认人员覆盖。 */
			String reason) {
	}
}
