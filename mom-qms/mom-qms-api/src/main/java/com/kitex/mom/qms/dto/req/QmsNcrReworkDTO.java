package com.kitex.mom.qms.dto.req;

import java.util.List;

/**
 * 仓储NCR原位整量返工请求与受权进度；ID、数量及业务时间均保留文本。
 */
public final class QmsNcrReworkDTO {
	private QmsNcrReworkDTO() {
	}

	/**
	 * @param requestId         永久请求号
	 * @param proposalId        已发布MRB编号
	 * @param parentOperationId 原REWORK操作编号
	 * @param quantity          原基本单位实际整量
	 * @param measures          实际返工措施
	 * @param evidence          完成凭证
	 */
	public record Complete(String requestId, String proposalId, String parentOperationId, String quantity,
	                       String measures, String evidence) {
	}

	/**
	 * @param requestId         作废永久原号
	 * @param proposalId        原MRB编号
	 * @param parentOperationId 原REWORK编号
	 * @param completionId      原完成记录编号
	 * @param reason            原记录人填写的纠错依据
	 */
	public record VoidCompletion(String requestId, String proposalId, String parentOperationId, String completionId,
	                             String reason) {
	}

	/**
	 * @param requestId         验证永久原号
	 * @param proposalId        原MRB编号
	 * @param parentOperationId 原REWORK编号
	 * @param completionId      有效完成编号；未完成REJECT可空
	 * @param decision          PASS/REJECT
	 * @param reviewEvidence    独立检验依据和实测结果
	 */
	public record Prepare(String requestId, String proposalId, String parentOperationId, String completionId,
	                      String decision, String reviewEvidence) {
	}

	/**
	 * 本地前准备拒绝永久摘要；不是WMS取消收据，不改变原件或其它原号的意图。
	 *
	 * @param version           固定协议版本1
	 * @param requestId         被永久拒绝的原准备号
	 * @param detailId          原分类明细编号
	 * @param proposalId        原MRB编号
	 * @param parentOperationId 原REWORK操作编号
	 * @param reviewerId        原提交者真实稳定用户编号
	 * @param tenantId          实际租户编号
	 * @param factoryCode       实际工厂编码
	 * @param fingerprint       完整规范Prepare和实际组织/人员的SHA256
	 * @param warehouseCode     已受权原仓编码
	 * @param reasonCode        明确本地前准备拒绝原因码
	 * @param reason            原拒绝实际原因，最多500字符
	 * @param recordedAt        永久拒绝提交事件时间，微秒精度
	 */
	public record NotPrepared(int version, String requestId, String detailId, String proposalId,
	                          String parentOperationId, String reviewerId, String tenantId, String factoryCode,
	                          String fingerprint,
	                          String warehouseCode, String reasonCode, String reason, String recordedAt) {
	}

	/**
	 * 原prepare端点的明确裁决；仅提交确认后的永久拒绝可安全解除浏览器原文锁。
	 *
	 * @param state       INTENT已有意图/NOT_PREPARED永久未准备
	 * @param notPrepared 永久未准备摘要，仅NOT_PREPARED非空
	 * @param progress    当前受权进度，不以拒绝事件覆盖真实业务进度
	 */
	public record PreparationOutcome(String state, NotPrepared notPrepared, Progress progress) {
	}

	/**
	 * @param requestId         必须为原验证号
	 * @param proposalId        冻结MRB编号
	 * @param parentOperationId 冻结REWORK编号
	 */
	public record Recover(String requestId, String proposalId, String parentOperationId) {
	}

	/**
	 * @param requestId         原验证号
	 * @param parentOperationId 原REWORK编号
	 * @param state             PREPARED/APPLIED/CANCELLED
	 * @param decision          原决定
	 * @param reviewerCode      原复核人工号
	 * @param preparedAt        准备时间
	 * @param finishedAt        终态确认时间，可空
	 */
	public record Attempt(String requestId, String parentOperationId, String state, String decision,
	                      String reviewerCode, String preparedAt, String finishedAt) {
	}

	/**
	 * @param completionId      原完成编号
	 * @param parentOperationId 原REWORK编号
	 * @param quantity          原基本单位实作量
	 * @param unit              冻结基本单位
	 * @param measures          原措施
	 * @param evidence          原凭证
	 * @param executorCode      原执行人工号
	 * @param completedAt       原完成时间
	 * @param voidRequestId     作废原号
	 * @param voidReason        纠错依据
	 * @param voidedAt          作废时间
	 */
	public record VoidFact(String completionId, String parentOperationId, String quantity, String unit, String measures,
	                       String evidence, String executorCode, String completedAt, String voidRequestId,
	                       String voidReason,
	                       String voidedAt) {
	}

	/**
	 * @param completionId      永久完成编号
	 * @param parentOperationId 原REWORK编号
	 * @param requestId         完成原号
	 * @param state             VALID有效/VOID已作废
	 * @param quantity          原基本单位实作量
	 * @param unit              冻结单位
	 * @param measures          实际措施
	 * @param evidence          完成凭证
	 * @param executorCode      完成人工号
	 * @param completedAt       原完成时间
	 */
	public record CompletionFact(String completionId, String parentOperationId, String requestId, String state,
	                             String quantity, String unit, String measures, String evidence, String executorCode,
	                             String completedAt) {
	}

	/**
	 * @param detailId            分类明细编号
	 * @param proposalId          当前MRB编号
	 * @param parentOperationId   当前REWORK编号
	 * @param stage               当前业务进度
	 * @param warehouseCode       受权原仓
	 * @param completionId        当前有效完成编号，可空
	 * @param completionRequestId 完成原号，可空
	 * @param executorCode        完成人工号，可空
	 * @param actualQuantity      实作量，可空
	 * @param originalQuantity    原分类基本单位整量文本
	 * @param unit                原件基本单位
	 * @param measures            完成措施，可空
	 * @param evidence            完成凭证，可空
	 * @param activeRequestId     原验证号，可空
	 * @param decision            冻结决定，可空
	 * @param reviewEvidence      冻结独立依据，可空
	 * @param reviewerCode        原复核人工号，可空
	 * @param remoteState         远端状态；未确认UNKNOWN
	 * @param history             各版本永久验证历史
	 * @param voidHistory         永久作废完成历史
	 * @param completionHistory   所有版本完整完成事实
	 */
	public record Progress(String detailId, String proposalId, String parentOperationId, String stage,
	                       String warehouseCode, String completionId, String completionRequestId, String executorCode,
	                       String actualQuantity, String originalQuantity, String unit, String measures,
	                       String evidence, String activeRequestId, String decision,
	                       String reviewEvidence, String reviewerCode, String remoteState, List<Attempt> history,
	                       List<VoidFact> voidHistory, List<CompletionFact> completionHistory) {
	}
}
