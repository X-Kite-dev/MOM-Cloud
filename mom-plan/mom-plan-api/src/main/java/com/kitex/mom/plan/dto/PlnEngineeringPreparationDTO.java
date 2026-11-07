package com.kitex.mom.plan.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * PLAN自有工程准备与实际终态合同。所有公开ID按文本，节点工时批准不表示整包或APS换版。
 */
public final class PlnEngineeringPreparationDTO {

	private PlnEngineeringPreparationDTO() {
	}

	/**
	 * 服务端按固定字段顺序规范化并永久保存，不接收客户端观察正文或范围集合。
	 */
	public record Command(
			/**
			 * PEP:原申请人ID:唯一尾号，同组织内永久唯一。
			 */
			String requestId,
			/**
			 * 当前真实MES原工单号。
			 */
			String taskCode,
			/**
			 * 正式目标发布ID十进制文本。
			 */
			String targetReleaseId,
			/**
			 * 正式目标原件SHA256。
			 */
			String targetContentHash,
			/**
			 * 已读取的PLAN完整分配观察摘要；漂移需重新准备新号。
			 */
			String expectedObservationFingerprint,
			/**
			 * 原准备原因，非空、最多1000字符，保持原文。
			 */
			String reason) {
	}

	/**
	 * 原号永久撤销，原准备正文不随撤销原因改变。
	 */
	public record CancelCommand(
			/**
			 * 完整原准备命令，未知结果仍沿原号原文。
			 */
			Command original,
			/**
			 * 独立撤销原因，非空且最多1000字符。
			 */
			String cancelReason) {
	}

	/**
	 * 仅服务端从原观察推导的来源锁键，不开放为客户端请求参数。
	 */
	public record SourceScope(
			/**
			 * ISSUE/BATCH/TASK。
			 */
			String kind,
			/**
			 * 所属PLAN原主键，内部Java Long。
			 */
			Long sourceId) {
	}

	/**
	 * 一次批量写入的完整来源，ID由服务端产生。
	 */
	public record SourceScopeWithId(
			/**
			 * 新不可变来源账主键。
			 */
			Long id,
			/**
			 * ISSUE/BATCH/TASK。
			 */
			String kind,
			/**
			 * 所属PLAN原主键。
			 */
			Long sourceId) {
	}

	/**
	 * 原准备与可信MES终结后的有效状态，原规范命令和来源身份始终保留。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Receipt(
			/**
			 * 固定PLAN_ENGINEERING_PREPARE_V2；原命令规范仍保持V1。
			 */
			String schemaVersion,
			/**
			 * 不可变准备父账ID文本。
			 */
			String id,
			/**
			 * 实际租户文本。
			 */
			String tenantId,
			/**
			 * 实际工厂。
			 */
			String factoryCode,
			/**
			 * 原规范命令。
			 */
			Command original,
			/**
			 * 原规范UTF8正文SHA256。
			 */
			String requestFingerprint,
			/**
			 * PREPARED来源保护中，CANCELLED永久撤销，APPLIED节点工时批准层生效。
			 */
			String status,
			/**
			 * 原申请人员ID文本。
			 */
			String applicantId,
			/**
			 * 原准备完成当地业务时间，未受理墓碑为空。
			 */
			String preparedAt,
			/**
			 * 原冻结工程发布ID，未受理墓碑为空。
			 */
			String sourceReleaseId,
			/**
			 * 原冻结工程SHA256，未受理墓碑为空。
			 */
			String sourceContentHash,
			/**
			 * 准备原观察UTF8正文SHA256，未受理墓碑为空。
			 */
			String observationSnapshotHash,
			/**
			 * 不可变来源数；列表未逐号取数时为空，未知不冒充0。
			 */
			Integer scopeCount,
			/**
			 * 实际撤销人员ID，未撤销为空。
			 */
			String cancelledBy,
			/**
			 * 实际撤销时间，未撤销为空。
			 */
			String cancelledAt,
			/**
			 * 原撤销原因，未撤销为空。
			 */
			String cancelReason,
			/**
			 * 完整V2合同支持节点工时采用；仍须按原号预核实际资格。
			 */
			boolean adoptionSupported,
			/**
			 * PLAN已核MES实际永久决策；未准备墓碑或未终结时为空。
			 */
			PlnEngineeringResolutionDTO.Resolution resolution) {
	}

	/**
	 * 库结构完整就绪后提供最近准备记录，不把未安装看作没有业务。
	 */
	public record Context(
			/**
			 * 当前模块是否完整安装就绪；部分安装直接失败。
			 */
			boolean supported,
			/**
			 * 当前组织原工单号。
			 */
			String taskCode,
			/**
			 * 最近50份实际记录，不包含大观察正文。
			 */
			List<Receipt> records,
			/**
			 * 仍有更多历史，原号精确读取仍可用。
			 */
			boolean hasMore,
			/**
			 * 完整V2合同支持节点工时采用，不能代替实际节点资格核验。
			 */
			boolean adoptionSupported) {
	}
}
