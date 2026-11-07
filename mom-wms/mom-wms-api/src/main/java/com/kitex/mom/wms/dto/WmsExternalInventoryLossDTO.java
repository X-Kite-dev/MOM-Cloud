package com.kitex.mom.wms.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 外部单组盘亏命令、权限上下文及真实回执。主键和数量始终以文本传输。
 */
public final class WmsExternalInventoryLossDTO {
	private WmsExternalInventoryLossDTO() {
	}

	/**
	 * 永久恢复使用的逐字原件。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Envelope(
			/** 实名持久命令原号。 */
			@Schema(description = "实名持久命令原号") String requestId,
			/** 首次准备后冻结的UTF8正文。 */
			@Schema(description = "首次准备后冻结的UTF8正文") String commandText) {
	}

	/**
	 * 仓级授权的实名组织范围。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record WarehouseScope(
			/** 实际租户正int64文本。 */
			@Schema(description = "实际租户正int64文本") String tenantId,
			/** 实名操作人正int64文本。 */
			@Schema(description = "实名操作人正int64文本") String actorId,
			/** 实际工厂编码。 */
			@Schema(description = "实际工厂编码") String factoryCode,
			/** 实际授权仓编码。 */
			@Schema(description = "实际授权仓编码") String warehouseCode) {
	}

	/**
	 * 实盘明细的实名组织范围。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record DetailScope(
			/** 实际租户正int64文本。 */
			@Schema(description = "实际租户正int64文本") String tenantId,
			/** 实名操作人正int64文本。 */
			@Schema(description = "实名操作人正int64文本") String actorId,
			/** 实际工厂编码。 */
			@Schema(description = "实际工厂编码") String factoryCode,
			/** 实际授权仓编码。 */
			@Schema(description = "实际授权仓编码") String warehouseCode,
			/** 盘点计划主键文本。 */
			@Schema(description = "盘点计划主键文本") String planId,
			/** 盘点计划明细主键文本。 */
			@Schema(description = "盘点计划明细主键文本") String planDetailId) {
	}

	/**
	 * 外部五动作互斥命令。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record ApprovalCommand(
			/** 协议或授权版本。 */
			@Schema(description = "协议或授权版本") int version,
			/** 实际组织与人员范围。 */
			@Schema(description = "实际组织与人员范围") DetailScope scope,
			/** 本次命令动作。 */
			@Schema(description = "本次命令动作") String action,
			/** 申请内容，其他动作为空。 */
			@Schema(description = "申请内容，其他动作为空") Submit submit,
			/** 核验意见，非审核动作为空。 */
			@Schema(description = "核验意见，非审核动作为空") Review review,
			/** 过账内容，其他动作为空。 */
			@Schema(description = "过账内容，其他动作为空") Post post) {
	}

	/**
	 * 首次申请冻结的差额、证件及精确分配。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Submit(
			/** 原账面数量，以盘点标准单位计。 */
			@Schema(description = "原账面数量，以盘点标准单位计") String expectedCurrentNumber,
			/** 复盘确认数量，以盘点标准单位计。 */
			@Schema(description = "复盘确认数量，以盘点标准单位计") String expectedInventoryNum,
			/** 当前分组复盘记录主键。 */
			@Schema(description = "当前分组复盘记录主键") String recountId,
			/** 库存依据SHA256。 */
			@Schema(description = "库存依据SHA256") String basisVersion,
			/** 单一外部货权组摘要。 */
			@Schema(description = "单一外部货权组摘要") String groupKey,
			/** 企业授权主键。 */
			@Schema(description = "企业授权主键") String mandateId,
			/** 冻结授权版本正整数文本。 */
			@Schema(description = "冻结授权版本正整数文本") String mandateVersion,
			/** 企业核验证件主键，非核验动作可空。 */
			@Schema(description = "企业核验证件主键，非核验动作可空") String evidenceId,
			/** 冻结文件摘要，非核验动作可空。 */
			@Schema(description = "冻结文件摘要，非核验动作可空") String evidenceSha256,
			/** 盘亏申请原因。 */
			@Schema(description = "盘亏申请原因") String reason,
			/** 按实物主键升序的精确分配。 */
			@Schema(description = "按实物主键升序的精确分配") List<LossStock> lossStocks) {
	}

	/**
	 * 一笔指定实物扣减。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record LossStock(
			/** 真实库存主键。 */
			@Schema(description = "真实库存主键") String stockId,
			/** 库存依据SHA256。 */
			@Schema(description = "库存依据SHA256") String basisVersion,
			/** 扣减数量，以原行标准单位计。 */
			@Schema(description = "扣减数量，以原行标准单位计") String quantity) {
	}

	/**
	 * 审批、驳回或撤销；仅批准须声明核验文件。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Review(
			/** 实际盘点差异审批主键。 */
			@Schema(description = "实际盘点差异审批主键") String approvalId,
			/** 原动作意见。 */
			@Schema(description = "原动作意见") String comment,
			/** 企业核验证件主键，非核验动作可空。 */
			@Schema(description = "企业核验证件主键，非核验动作可空") String evidenceId,
			/** 冻结文件摘要，非核验动作可空。 */
			@Schema(description = "冻结文件摘要，非核验动作可空") String evidenceSha256,
			/** 批准人明确核验原件，其他动作为空。 */
			@Schema(description = "批准人明确核验原件，其他动作为空") Boolean verified) {
	}

	/**
	 * 已批准实例的当次真实过账。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Post(
			/** 实际盘点差异审批主键。 */
			@Schema(description = "实际盘点差异审批主键") String approvalId) {
	}

	/**
	 * 仓级企业授权动作。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record MandateCommand(
			/** 协议或授权版本。 */
			@Schema(description = "协议或授权版本") int version,
			/** 实际组织与人员范围。 */
			@Schema(description = "实际组织与人员范围") WarehouseScope scope,
			/** 本次命令动作。 */
			@Schema(description = "本次命令动作") String action,
			/** 新提议内容，其他动作为空。 */
			@Schema(description = "新提议内容，其他动作为空") Proposal proposal,
			/** 决定内容，提议时为空。 */
			@Schema(description = "决定内容，提议时为空") Decision decision) {
	}

	/**
	 * 外部主体仓级授权提议。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Proposal(
			/** 客供或寄售货权类别。 */
			@Schema(description = "客供或寄售货权类别") String ownerType,
			/** 当前工厂真实货主主键。 */
			@Schema(description = "当前工厂真实货主主键") String mdmOwnerId,
			/** 外部主体业务编码。 */
			@Schema(description = "外部主体业务编码") String ownerCode,
			/** 授权方向，固定LOSS。 */
			@Schema(description = "授权方向，固定LOSS") String direction,
			/** 授权起始日期，含当日。 */
			@Schema(description = "授权起始日期，含当日") String validFrom,
			/** 授权截止日期，含当日。 */
			@Schema(description = "授权截止日期，含当日") String validUntil,
			/** 企业授权文号。 */
			@Schema(description = "企业授权文号") String authorityNo) {
	}

	/**
	 * 原授权版本上的审核或撤销。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Decision(
			/** 企业授权主键。 */
			@Schema(description = "企业授权主键") String mandateId,
			/** 操作前授权版本。 */
			@Schema(description = "操作前授权版本") String expectedVersion,
			/** 原动作意见。 */
			@Schema(description = "原动作意见") String comment) {
	}

	/**
	 * Web登记本次外部货主确认原件。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record EvidenceCommand(
			/** 协议或授权版本。 */
			@Schema(description = "协议或授权版本") int version,
			/** 实际组织与人员范围。 */
			@Schema(description = "实际组织与人员范围") DetailScope scope,
			/** 本次命令动作。 */
			@Schema(description = "本次命令动作") String action,
			/** 登记时完整业务及文件属性。 */
			@Schema(description = "登记时完整业务及文件属性") Registration registration) {
	}

	/**
	 * 登记前须与原件字节及实际差额逐项核对。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Registration(
			/** 当前分组复盘记录主键。 */
			@Schema(description = "当前分组复盘记录主键") String recountId,
			/** 库存依据SHA256。 */
			@Schema(description = "库存依据SHA256") String basisVersion,
			/** 单一外部货权组摘要。 */
			@Schema(description = "单一外部货权组摘要") String groupKey,
			/** 企业授权主键。 */
			@Schema(description = "企业授权主键") String mandateId,
			/** 冻结授权版本正整数文本。 */
			@Schema(description = "冻结授权版本正整数文本") String mandateVersion,
			/** 客供或寄售货权类别。 */
			@Schema(description = "客供或寄售货权类别") String ownerType,
			/** 当前工厂真实货主主键。 */
			@Schema(description = "当前工厂真实货主主键") String mdmOwnerId,
			/** 外部主体业务编码。 */
			@Schema(description = "外部主体业务编码") String ownerCode,
			/** 原实物商业来源单号。 */
			@Schema(description = "原实物商业来源单号") String sourceBusinessNo,
			/** 明细物料编码。 */
			@Schema(description = "明细物料编码") String matCode,
			/** 原实物标准计量单位。 */
			@Schema(description = "原实物标准计量单位") String matUnit,
			/** 盘亏绝对数量，以原标准单位计。 */
			@Schema(description = "盘亏绝对数量，以原标准单位计") String lossQuantity,
			/** 货主确认文号。 */
			@Schema(description = "货主确认文号") String confirmationNo,
			/** 货主确认文件签发日。 */
			@Schema(description = "货主确认文件签发日") String confirmationDate,
			/** 原文件安全名称。 */
			@Schema(description = "原文件安全名称") String fileName,
			/** 实际原件媒体类型。 */
			@Schema(description = "实际原件媒体类型") String mime,
			/** 原始文件字节数。 */
			@Schema(description = "原始文件字节数") int byteLength,
			/** 原始文件SHA256小写十六进制。 */
			@Schema(description = "原始文件SHA256小写十六进制") String sha256) {
	}

	/**
	 * 当前实名操作范围、原号及分动作权限。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Context<S>(
			/** 协议或授权版本。 */
			@Schema(description = "协议或授权版本") int version,
			/** 原号域OM1、OE1或OA1。 */
			@Schema(description = "原号域OM1、OE1或OA1") String domain,
			/** 实际组织与人员范围。 */
			@Schema(description = "实际组织与人员范围") S scope,
			/** 实名人员账号快照。 */
			@Schema(description = "实名人员账号快照") String actorCode,
			/** 实名持久命令原号。 */
			@Schema(description = "实名持久命令原号") String requestId,
			/** 实际盘点计划单号，授权域为空。 */
			@Schema(description = "实际盘点计划单号，授权域为空") String planNo,
			/** 明细物料编码。 */
			@Schema(description = "明细物料编码") String matCode,
			/** 原实物标准计量单位。 */
			@Schema(description = "原实物标准计量单位") String matUnit,
			/** 当前可执行动作。 */
			@Schema(description = "当前可执行动作") List<String> executeActions,
			/** 当前可永久结束的动作。 */
			@Schema(description = "当前可永久结束的动作") List<String> endActions) {
	}

	/**
	 * 永久受理结果；历史受理按当时事件回读。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Resolution<S>(
			/** 协议或授权版本。 */
			@Schema(description = "协议或授权版本") int version,
			/** 原号域OM1、OE1或OA1。 */
			@Schema(description = "原号域OM1、OE1或OA1") String domain,
			/** 当前业务或原号终态。 */
			@Schema(description = "当前业务或原号终态") String status,
			/** 实际组织与人员范围。 */
			@Schema(description = "实际组织与人员范围") S scope,
			/** 实名持久命令原号。 */
			@Schema(description = "实名持久命令原号") String requestId,
			/** 本次命令动作。 */
			@Schema(description = "本次命令动作") String action,
			/** 实际迁移事件主键，未执行时为空。 */
			@Schema(description = "实际迁移事件主键，未执行时为空") String effectId,
			/** 实际业务源主键。 */
			@Schema(description = "实际业务源主键") String sourceId,
			/** 源行当时办理状态。 */
			@Schema(description = "源行当时办理状态") String resultStatus,
			/** 实名人员账号快照。 */
			@Schema(description = "实名人员账号快照") String actorCode,
			/** 源实际动作发生时间。 */
			@Schema(description = "源实际动作发生时间") String actionTime,
			/** 数据库永久收录时间。 */
			@Schema(description = "数据库永久收录时间") String recordedAt) {
	}

	/**
	 * 完整盘点范围与外部单组损益办理依据。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Basis(
			/** 能否办理本次单组盘亏。 */
			@Schema(description = "能否办理本次单组盘亏") boolean eligible,
			/** 不具备办理条件的原因，允许时为空。 */
			@Schema(description = "不具备办理条件的原因，允许时为空") String reasonCode,
			/** 当前分组复盘记录主键。 */
			@Schema(description = "当前分组复盘记录主键") String recountId,
			/** 库存依据SHA256。 */
			@Schema(description = "库存依据SHA256") String basisVersion,
			/** 单一外部货权组摘要。 */
			@Schema(description = "单一外部货权组摘要") String groupKey,
			/** 原账面数量，以盘点标准单位计。 */
			@Schema(description = "原账面数量，以盘点标准单位计") String expectedCurrentNumber,
			/** 复盘确认数量，以盘点标准单位计。 */
			@Schema(description = "复盘确认数量，以盘点标准单位计") String expectedInventoryNum,
			/** 盘亏绝对数量，以原标准单位计。 */
			@Schema(description = "盘亏绝对数量，以原标准单位计") String lossQuantity,
			/** 明细物料编码。 */
			@Schema(description = "明细物料编码") String matCode,
			/** 原实物标准计量单位。 */
			@Schema(description = "原实物标准计量单位") String matUnit,
			/** 当前实名MDM货主投影。 */
			@Schema(description = "当前实名MDM货主投影") Owner owner,
			/** 原实物商业来源单号。 */
			@Schema(description = "原实物商业来源单号") String sourceBusinessNo,
			/** 当前生效授权。 */
			@Schema(description = "当前生效授权") Mandate mandate,
			/** 全部原位库存与实际实收来源。 */
			@Schema(description = "全部原位库存与实际实收来源") List<Source> sources) {
	}

	/**
	 * 每一行原始实收和当前实物的一一对应。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Source(
			/** 真实库存主键。 */
			@Schema(description = "真实库存主键") String stockId,
			/** 原实收入库单主键。 */
			@Schema(description = "原实收入库单主键") String inBillId,
			/** 原实收入库单号。 */
			@Schema(description = "原实收入库单号") String inBillNo,
			/** 原实收明细主键。 */
			@Schema(description = "原实收明细主键") String inBillDetailId,
			/** 唯一实际入库流水主键。 */
			@Schema(description = "唯一实际入库流水主键") String inboundFlowId,
			/** 所属真实物流操作号。 */
			@Schema(description = "所属真实物流操作号") String operationRequestId,
			/** 原实收标准数量。 */
			@Schema(description = "原实收标准数量") String inboundQuantity,
			/** 当前实物标准数量。 */
			@Schema(description = "当前实物标准数量") String currentQuantity,
			/** 原实物标准计量单位。 */
			@Schema(description = "原实物标准计量单位") String matUnit) {
	}

	/**
	 * 按数值实物编号升序的候选页。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Candidates(
			/** 本页结果。 */
			@Schema(description = "本页结果") List<Candidate> rows,
			/** 下一页升序库存游标，末页为空。 */
			@Schema(description = "下一页升序库存游标，末页为空") String nextAfterStockId) {
	}

	/**
	 * 一行实物的可选性和可用数量。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Candidate(
			/** 真实库存主键。 */
			@Schema(description = "真实库存主键") String stockId,
			/** 库存依据SHA256。 */
			@Schema(description = "库存依据SHA256") String basisVersion,
			/** 当前实物标准数量。 */
			@Schema(description = "当前实物标准数量") String currentQuantity,
			/** 扣除预留后的可用标准数量。 */
			@Schema(description = "扣除预留后的可用标准数量") String availableQuantity,
			/** 原实物标准计量单位。 */
			@Schema(description = "原实物标准计量单位") String matUnit,
			/** 原实物是否按序列整件管理。 */
			@Schema(description = "原实物是否按序列整件管理") boolean serialManaged,
			/** 当前人员及业务条件是否可选。 */
			@Schema(description = "当前人员及业务条件是否可选") boolean selectable,
			/** 不具备办理条件的原因，允许时为空。 */
			@Schema(description = "不具备办理条件的原因，允许时为空") String reasonCode,
			/** 原实物批次，可空。 */
			@Schema(description = "原实物批次，可空") String matBatch,
			/** 原实物条码或序列号，可空。 */
			@Schema(description = "原实物条码或序列号，可空") String matBarcode,
			/** 实际存放容器，可空。 */
			@Schema(description = "实际存放容器，可空") String containerCode,
			/** 当前实际库位。 */
			@Schema(description = "当前实际库位") String siteCode,
			/** 原实物供应商名称，可空。 */
			@Schema(description = "原实物供应商名称，可空") String supplierName) {
	}

	/**
	 * 本仓授权历史分页。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record MandatePage(
			/** 本页结果。 */
			@Schema(description = "本页结果") List<Mandate> rows,
			/** 下一页降序授权游标，末页为空。 */
			@Schema(description = "下一页降序授权游标，末页为空") String nextBeforeMandateId) {
	}

	/**
	 * 保留原批准与撤销分别发生的事实。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Mandate(
			/** 实际源行主键。 */
			@Schema(description = "实际源行主键") String id,
			/** 协议或授权版本。 */
			@Schema(description = "协议或授权版本") String version,
			/** 当前业务或原号终态。 */
			@Schema(description = "当前业务或原号终态") String status,
			/** 客供或寄售货权类别。 */
			@Schema(description = "客供或寄售货权类别") String ownerType,
			/** 当前工厂真实货主主键。 */
			@Schema(description = "当前工厂真实货主主键") String mdmOwnerId,
			/** 外部主体业务编码。 */
			@Schema(description = "外部主体业务编码") String ownerCode,
			/** 原主体名称快照。 */
			@Schema(description = "原主体名称快照") String ownerName,
			/** 授权方向，固定LOSS。 */
			@Schema(description = "授权方向，固定LOSS") String direction,
			/** 授权起始日期，含当日。 */
			@Schema(description = "授权起始日期，含当日") String validFrom,
			/** 授权截止日期，含当日。 */
			@Schema(description = "授权截止日期，含当日") String validUntil,
			/** 企业授权文号。 */
			@Schema(description = "企业授权文号") String authorityNo,
			/** 提议人主键。 */
			@Schema(description = "提议人主键") String proposerId,
			/** 提议人账号。 */
			@Schema(description = "提议人账号") String proposerCode,
			/** 提议实际发生时间。 */
			@Schema(description = "提议实际发生时间") String proposedAt,
			/** 审核人主键，待审为空。 */
			@Schema(description = "审核人主键，待审为空") String reviewerId,
			/** 审核人账号，待审为空。 */
			@Schema(description = "审核人账号，待审为空") String reviewerCode,
			/** 审核实际时间，待审为空。 */
			@Schema(description = "审核实际时间，待审为空") String reviewedAt,
			/** 原审核意见，待审为空。 */
			@Schema(description = "原审核意见，待审为空") String reviewComment,
			/** 撤销人主键，未撤销为空。 */
			@Schema(description = "撤销人主键，未撤销为空") String revokerId,
			/** 撤销人账号，未撤销为空。 */
			@Schema(description = "撤销人账号，未撤销为空") String revokerCode,
			/** 撤销实际时间，未撤销为空。 */
			@Schema(description = "撤销实际时间，未撤销为空") String revokedAt,
			/** 原撤销意见，未撤销为空。 */
			@Schema(description = "原撤销意见，未撤销为空") String revokeComment,
			/** 当前数据库业务日期内有效且已批准。 */
			@Schema(description = "当前数据库业务日期内有效且已批准") boolean current) {
	}

	/**
	 * 原件元数据和一次性申请绑定。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Evidence(
			/** 实际源行主键。 */
			@Schema(description = "实际源行主键") String id,
			/** 实名持久命令原号。 */
			@Schema(description = "实名持久命令原号") String requestId,
			/** 登记时完整业务及文件属性。 */
			@Schema(description = "登记时完整业务及文件属性") Registration registration,
			/** 原件登记人主键。 */
			@Schema(description = "原件登记人主键") String registeredBy,
			/** 原件登记账号。 */
			@Schema(description = "原件登记账号") String registeredCode,
			/** 原件实际登记时间。 */
			@Schema(description = "原件实际登记时间") String registeredAt,
			/** 实际盘点差异审批主键。 */
			@Schema(description = "实际盘点差异审批主键") String approvalId,
			/** 当前人员及业务条件是否可选。 */
			@Schema(description = "当前人员及业务条件是否可选") boolean selectable) {
	}

	/**
	 * 本计划明细真实审批事件页。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record History(
			/** 本页结果。 */
			@Schema(description = "本页结果") List<HistoryRow> rows,
			/** 下一页降序事件游标，末页为空。 */
			@Schema(description = "下一页降序事件游标，末页为空") String nextBeforeEffectId) {
	}

	/**
	 * 当次审批迁移及真实出库回执。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record HistoryRow(
			/** 实际迁移事件主键，未执行时为空。 */
			@Schema(description = "实际迁移事件主键，未执行时为空") String effectId,
			/** 实名持久命令原号。 */
			@Schema(description = "实名持久命令原号") String requestId,
			/** 本次命令动作。 */
			@Schema(description = "本次命令动作") String action,
			/** 实际盘点差异审批主键。 */
			@Schema(description = "实际盘点差异审批主键") String approvalId,
			/** 源行当时办理状态。 */
			@Schema(description = "源行当时办理状态") String resultStatus,
			/** 实名操作人正int64文本。 */
			@Schema(description = "实名操作人正int64文本") String actorId,
			/** 实名人员账号快照。 */
			@Schema(description = "实名人员账号快照") String actorCode,
			/** 源实际动作发生时间。 */
			@Schema(description = "源实际动作发生时间") String actionTime,
			/** 盘亏申请原因。 */
			@Schema(description = "盘亏申请原因") String reason,
			/** 原动作意见。 */
			@Schema(description = "原动作意见") String comment,
			/** 企业核验证件主键，非核验动作可空。 */
			@Schema(description = "企业核验证件主键，非核验动作可空") String evidenceId,
			/** 冻结文件摘要，非核验动作可空。 */
			@Schema(description = "冻结文件摘要，非核验动作可空") String evidenceSha256,
			/** 真实出库的逐行回执，其他动作为空数组。 */
			@Schema(description = "真实出库的逐行回执，其他动作为空数组") List<PostedLine> postedLines) {
	}

	/**
	 * 本次精确扣减对应真实出库头行与流。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record PostedLine(
			/** 实际盘亏出库单主键。 */
			@Schema(description = "实际盘亏出库单主键") String outBillId,
			/** 实际盘亏出库单号。 */
			@Schema(description = "实际盘亏出库单号") String outBillNo,
			/** 实际出库明细主键。 */
			@Schema(description = "实际出库明细主键") String outDetailId,
			/** 实际出库流水主键。 */
			@Schema(description = "实际出库流水主键") String flowId,
			/** 真实库存主键。 */
			@Schema(description = "真实库存主键") String stockId,
			/** 扣减数量，以原行标准单位计。 */
			@Schema(description = "扣减数量，以原行标准单位计") String quantity,
			/** 原实物标准计量单位。 */
			@Schema(description = "原实物标准计量单位") String matUnit,
			/** 所属真实物流操作号。 */
			@Schema(description = "所属真实物流操作号") String operationRequestId) {
	}

	/**
	 * 受控MDM接口在实际工厂核得的最小主体投影。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Owner(
			/** 当前工厂货主主键。 */
			@Schema(description = "当前工厂货主主键") String id,
			/** 认证租户主键。 */
			@Schema(description = "认证租户主键") String tenantId,
			/** 认证工厂编码。 */
			@Schema(description = "认证工厂编码") String factoryCode,
			/** 客供或寄售类型。 */
			@Schema(description = "客供或寄售类型") String ownerType,
			/** 原主体编码。 */
			@Schema(description = "原主体编码") String code,
			/** 原主体名称。 */
			@Schema(description = "原主体名称") String name,
			/** 主体启用状态，未知可空。 */
			@Schema(description = "主体启用状态") Boolean enabled,
			/** 供应商审核状态，客户为空。 */
			@Schema(description = "供应商审核状态") String supplierCheckStatus,
			/** 按启用状态与供应商审核状态判定的可用性。 */
			@Schema(description = "主体可用性") Boolean eligible) {
	}

	/**
	 * 从已受理SUBMIT真实事件投影的申请快照。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record FrozenAllocations(
			/** 当前实名读取人的租户、工厂、仓与盘点明细范围。 */
			@Schema(description = "当前实名读取人的租户、工厂、仓与盘点明细范围") DetailScope scope,
			/** 原盘亏审批主键，正整数文本。 */
			@Schema(description = "原盘亏审批主键，正整数文本") String approvalId,
			/** 原申请冻结物料编码。 */
			@Schema(description = "原申请冻结物料编码") String matCode,
			/** 原实物标准计量单位。 */
			@Schema(description = "原实物标准计量单位") String matUnit,
			/** 原申请货权类型，客供或寄售。 */
			@Schema(description = "原申请货权类型，客供或寄售") String ownerType,
			/** 原申请货主编码。 */
			@Schema(description = "原申请货主编码") String ownerCode,
			/** 原申请货主名称。 */
			@Schema(description = "原申请货主名称") String ownerName,
			/** 原冻结商业来源单号。 */
			@Schema(description = "原冻结商业来源单号") String sourceBusinessNo,
			/** 原盘亏总量，以原标准单位计，精确十进制文本。 */
			@Schema(description = "原盘亏总量，以原标准单位计，精确十进制文本") String lossQuantity,
			/** 原申请选定的逐实物扣量，按冻结分配顺序。 */
			@Schema(description = "原申请选定的逐实物扣量，按冻结分配顺序") List<FrozenAllocation> rows) {
	}

	/**
	 * 申请时一行精确盘亏分配及原始实收信息。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record FrozenAllocation(
			/** 原实物库存主键，正整数文本。 */
			@Schema(description = "原实物库存主键，正整数文本") String stockId,
			/** 该实物获申请的扣量，以原标准单位计。 */
			@Schema(description = "该实物获申请的扣量，以原标准单位计") String quantity,
			/** 申请时该实物的库存量，以原标准单位计。 */
			@Schema(description = "申请时该实物的库存量，以原标准单位计") String currentQuantity,
			/** 申请时扣除预留后的可用量，以原标准单位计。 */
			@Schema(description = "申请时扣除预留后的可用量，以原标准单位计") String availableQuantity,
			/** 原实物标准计量单位。 */
			@Schema(description = "原实物标准计量单位") String matUnit,
			/** 原实物批次，可空。 */
			@Schema(description = "原实物批次，可空") String matBatch,
			/** 原实物条码或序列号，可空。 */
			@Schema(description = "原实物条码或序列号，可空") String matBarcode,
			/** 原容器编码，可空。 */
			@Schema(description = "原容器编码，可空") String containerCode,
			/** 申请时实物所在库位编码。 */
			@Schema(description = "申请时实物所在库位编码") String siteCode,
			/** 原实物供应商名称，可空。 */
			@Schema(description = "原实物供应商名称，可空") String supplierName,
			/** 形成该实物的原始实收入库单号。 */
			@Schema(description = "形成该实物的原始实收入库单号") String inBillNo) {
	}
}
