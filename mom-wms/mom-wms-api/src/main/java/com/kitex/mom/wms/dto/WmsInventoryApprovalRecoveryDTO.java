package com.kitex.mom.wms.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 盘点审批原号恢复V1；所有业务ID与数量按文本传输，可空字段始终显式输出null。
 */
public final class WmsInventoryApprovalRecoveryDTO {
	private WmsInventoryApprovalRecoveryDTO() {
	}

	/**
	 * 实际认证组织与原业务对象，客户端不能扩大此范围。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Scope(
			/** 实际租户正int64文本。 */
			@Schema(description = "实际租户正int64文本") String tenantId,
			/** 实名人员正int64文本。 */
			@Schema(description = "实名人员正int64文本") String actorId,
			/** 实际工厂编码。 */
			@Schema(description = "实际工厂编码") String factoryCode,
			/** 原实际仓库编码。 */
			@Schema(description = "原实际仓库编码") String warehouseCode,
			/** 原计划正int64文本。 */
			@Schema(description = "原计划正int64文本") String planId,
			/** 原明细正int64文本。 */
			@Schema(description = "原明细正int64文本") String planDetailId) {
	}

	/**
	 * 首存后逐字重放的不可变信封。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Envelope(
			/** 本人IA1原号。 */
			@Schema(description = "本人IA1原号") String requestId,
			/** 冻结JSON原文；UTF8最多128KiB。 */
			@Schema(description = "冻结JSON原文；UTF8最多128KiB") String commandText) {
	}

	/**
	 * 静态动作合同，submit与review按动作互斥。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Command(
			/** 固定协议版本1。 */
			@Schema(description = "固定协议版本1") int version,
			/** 六项实际组织对象范围。 */
			@Schema(description = "六项实际组织对象范围") Scope scope,
			/** SUBMIT、APPROVE、REJECT或REVOKE。 */
			@Schema(description = "SUBMIT、APPROVE、REJECT或REVOKE") String action,
			/** 申请内容；其他动作显式null。 */
			@Schema(description = "申请内容；其他动作显式null") Submit submit,
			/** 审核内容；申请动作显式null。 */
			@Schema(description = "审核内容；申请动作显式null") Review review) {
	}

	/**
	 * 申请静态依据，动态规则由原正式服务核验。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Submit(
			/** 原账面数量，单位同明细，非负十进制文本。 */
			@Schema(description = "原账面数量，单位同明细，非负十进制文本") String expectedCurrentNumber,
			/** 原实盘数量，单位同明细，非负十进制文本。 */
			@Schema(description = "原实盘数量，单位同明细，非负十进制文本") String expectedInventoryNum,
			/** 固定十二项选定物料属性。 */
			@Schema(description = "固定十二项选定物料属性") Detail detail,
			/** 盘盈自有确认；盘亏为false。 */
			@Schema(description = "盘盈自有确认；盘亏为false") boolean ownershipConfirmed,
			/** 指定实物0至100项，按正int64库存ID升序。 */
			@Schema(description = "指定实物0至100项，按正int64库存ID升序") java.util.List<LossStock> lossStocks,
			/** 申请原因，规范后1至500个UTF16字符。 */
			@Schema(description = "申请原因，规范后1至500个UTF16字符") String reason) {
	}

	/**
	 * 原八项物性及自有货权、来源、单位。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Detail(
			/** 物料批次；允许null。 */
			@Schema(description = "物料批次；允许null") String matBatch,
			/** 供应商编码；允许null。 */
			@Schema(description = "供应商编码；允许null") String supplierCode,
			/** 供应商名称；允许null。 */
			@Schema(description = "供应商名称；允许null") String supplierName,
			/** 容器编码；允许null。 */
			@Schema(description = "容器编码；允许null") String containerCode,
			/** 物料条码；允许null。 */
			@Schema(description = "物料条码；允许null") String matBarcode,
			/** 真实ISO生产日期；允许null。 */
			@Schema(description = "真实ISO生产日期；允许null") String matProduceDate,
			/** 有效期天数，非负int32；允许null。 */
			@Schema(description = "有效期天数，非负int32；允许null") Integer matValidPeriod,
			/** 真实ISO到期日期；允许null。 */
			@Schema(description = "真实ISO到期日期；允许null") String matValidEndDate,
			/** 货权类型固定OWNED。 */
			@Schema(description = "货权类型固定OWNED") String stockOwnerType,
			/** 自有货主编码固定null。 */
			@Schema(description = "自有货主编码固定null") String stockOwnerCode,
			/** 盘盈来源单号；允许null，最多128字符。 */
			@Schema(description = "盘盈来源单号；允许null，最多128字符") String sourceBusinessNo,
			/** 原计划计量单位。 */
			@Schema(description = "原计划计量单位") String matUnit) {
	}

	/**
	 * 盘亏实物分配依据，不允许无损约束外的舍入。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record LossStock(
			/** 实际库存正int64文本。 */
			@Schema(description = "实际库存正int64文本") String stockId,
			/** 64位小写十六进制依据。 */
			@Schema(description = "64位小写十六进制依据") String basisVersion,
			/** 分配量，原单位，正数最多8位小数且Double无损。 */
			@Schema(description = "分配量，原单位，正数最多8位小数且Double无损") String quantity) {
	}

	/**
	 * 原审批对象及本人意见，结论从动作派生。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Review(
			/** 原审批正int64文本。 */
			@Schema(description = "原审批正int64文本") String approvalId,
			/** 审核或撤销意见，规范后1至500个UTF16字符。 */
			@Schema(description = "审核或撤销意见，规范后1至500个UTF16字符") String comment) {
	}

	/**
	 * 可信身份、原对象展示元数据和当前能力。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Context(
			/** 固定版本1。 */
			@Schema(description = "固定版本1") int version,
			/** 服务端实际六项范围。 */
			@Schema(description = "服务端实际六项范围") Scope scope,
			/** 实名账号。 */
			@Schema(description = "实名账号") String actorCode,
			/** 原计划号。 */
			@Schema(description = "原计划号") String planNo,
			/** 原物料编码。 */
			@Schema(description = "原物料编码") String matCode,
			/** 原物料名称；未知为null。 */
			@Schema(description = "原物料名称；未知为null") String matName,
			/** 原计量单位；未知为null。 */
			@Schema(description = "原计量单位；未知为null") String matUnit,
			/** 实际仓当前移动作业开关。 */
			@Schema(description = "实际仓当前移动作业开关") boolean mobileEnabled,
			/** 当前真实执行岗位与业务准入。 */
			@Schema(description = "当前真实执行岗位与业务准入") boolean canExecute,
			/** 当前真实执行岗位与工厂写权。 */
			@Schema(description = "当前真实执行岗位与工厂写权") boolean canEnd,
			/** 服务端安全UUIDv4新意图号。 */
			@Schema(description = "服务端安全UUIDv4新意图号") String nextRequestId) {
	}

	/**
	 * 固定原时点回执，不随后续审批或调账状态改变。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Resolution(
			/** 固定版本1。 */
			@Schema(description = "固定版本1") int version,
			/** ABSENT、APPLIED或ENDED永久结果。 */
			@Schema(description = "ABSENT、APPLIED或ENDED永久结果") String status,
			/** 原请求六项范围。 */
			@Schema(description = "原请求六项范围") Scope scope,
			/** 原请求号。 */
			@Schema(description = "原请求号") String requestId,
			/** 原四种动作之一。 */
			@Schema(description = "原四种动作之一") String action,
			/** 真实审批ID；ABSENT及ENDED为null。 */
			@Schema(description = "真实审批ID；ABSENT及ENDED为null") String approvalId,
			/** 原事件PENDING、APPROVED、REJECTED、REVOKED；非APPLIED为null。 */
			@Schema(description = "原事件PENDING、APPROVED、REJECTED、REVOKED；非APPLIED为null") String resultStatus,
			/** 真实事件或结束人ID；ABSENT为null。 */
			@Schema(description = "真实事件或结束人ID；ABSENT为null") String actorId,
			/** 真实事件账号；ABSENT为null。 */
			@Schema(description = "真实事件账号；ABSENT为null") String actorCode,
			/** DB原事件无时区时间，含秒最多6位小数；非APPLIED为null。 */
			@Schema(description = "DB原事件无时区时间，含秒最多6位小数；非APPLIED为null") String actionTime,
			/** DB收件无时区时间，含秒最多6位小数；ABSENT为null。 */
			@Schema(description = "DB收件无时区时间，含秒最多6位小数；ABSENT为null") String recordedAt) {
	}
}
