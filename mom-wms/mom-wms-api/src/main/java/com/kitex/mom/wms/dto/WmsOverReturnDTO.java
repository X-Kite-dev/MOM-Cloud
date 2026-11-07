package com.kitex.mom.wms.dto;

import java.util.List;

/**
 * 普通调拨溢收实际返还协议；所有ID和数量均以文本跨端传递。
 */
public final class WmsOverReturnDTO {
	private WmsOverReturnDTO() {
	}

	/**
	 * 实际身份与仓级能力；恢复能力独立于今日开关。
	 */
	public record Context(
			/** 当前实际租户ID。 */
			String tenantId,
			/** 真实登录用户正ID。 */
			String userId,
			/** 等于真实用户ID。 */
			String actorId,
			/** 已授权实际工厂。 */
			String factoryCode,
			/** 原调出仓。 */
			String warehouseCode,
			/** 原仓查看资格。 */
			boolean canView,
			/** 今日新批次实收资格。 */
			boolean canReceive,
			/** 原仓结束本人未受理号码资格。 */
			boolean canEnd,
			/** 今日手机开关。 */
			boolean mobileEnabled,
			/** 服务端OR1人员UUIDv4号，无预占。 */
			String nextRequestId) {
	}

	/**
	 * 原号全文信封，receive、lookup、end共用。
	 */
	public record Envelope(
			/** 服务端分配且原封保存的请求号。 */
			String requestId,
			/** 首次提交前冻结的UTF8全文。 */
			String commandText) {
	}

	/**
	 * Command业务合同。
	 */
	public record Command(
			/** 协议版本，固定1。 */
			int v,
			/** 原差异单正ID。 */
			String caseId,
			/** 原调出仓编码。 */
			String warehouseCode,
			/** 实际签收证据，1至1000字。 */
			String evidence,
			/** 本批唯一补扣片段，最多500条。 */
			List<ReceiveLine> lines) {
	}

	/**
	 * ReceiveLine业务合同。
	 */
	public record ReceiveLine(
			/** 原补扣流水正ID。 */
			String adjustmentFlowId,
			/** 原单位本次数量，正十进制20,6且Double往返精确。 */
			String quantity,
			/** 原件单位。 */
			String unit,
			/** 实际扫码，可空且显式null。 */
			String barcode,
			/** 实际收回库位。 */
			String siteCode,
			/** 实际收回容器，可空且显式null。 */
			String containerCode) {
	}

	/**
	 * Effect业务合同。
	 */
	public record Effect(
			/** 原补扣流水ID。 */
			String adjustmentFlowId,
			/** 差异明细ID。 */
			String varianceLineId,
			/** 原物料编码。 */
			String materialCode,
			/** 原批次，可空。 */
			String batch,
			/** 原数量单位。 */
			String unit,
			/** 实际扫码，可空。 */
			String barcode,
			/** 本次实收数量文本。 */
			String quantity,
			/** 实际库位。 */
			String siteCode,
			/** 实际容器，可空。 */
			String containerCode,
			/** 本次真实新增库存正ID。 */
			String stockId,
			/** 本次真实OVER_RETURN入库流水正ID。 */
			String inboundFlowId,
			/** 本次后片段累计数量。 */
			String receivedQuantity,
			/** 本次后片段余量。 */
			String remainingQuantity) {
	}

	/**
	 * Receipt业务合同。
	 */
	public record Receipt(
			/** 原请求号。 */
			String requestId,
			/** NOT_FOUND、APPLIED或CANCELLED。 */
			String status,
			/** 原差异单ID。 */
			String caseId,
			/** 原调拨单ID。 */
			String billId,
			/** 实际原仓。 */
			String warehouseCode,
			/** 原办理用户正ID。 */
			String actorId,
			/** 原办理人；NOT_FOUND为空。 */
			String operatorName,
			/** 原终态时间；NOT_FOUND为空。 */
			String handledTime,
			/** 当时状态；非APPLIED为空。 */
			String afterStatus,
			/** 永久真实入库效果，非APPLIED为空数组。 */
			List<Effect> effects,
			/** 原APPLIED签收证据；未受理或已结束为空。 */
			String evidence) {
	}

	/**
	 * Pending业务合同。
	 */
	public record Pending(
			/** 差异单ID。 */
			String caseId,
			/** 差异单号。 */
			String caseNo,
			/** 原调拨单ID。 */
			String billId,
			/** 原调拨单号。 */
			String billNo,
			/** 源仓。 */
			String sourceWarehouseCode,
			/** 目标仓。 */
			String targetWarehouseCode,
			/** 返还状态。 */
			String status,
			/** 真实发出时间。 */
			String dispatchTime) {
	}

	/**
	 * Page业务合同。
	 */
	public record Page(
			/** 授权过滤后的案件。 */
			List<Pending> records,
			/** 当前页。 */
			long current,
			/** 每页条数。 */
			long size,
			/** 同条件总条数。 */
			long total) {
	}

	/**
	 * Line业务合同。
	 */
	public record Line(
			/** 差异明细ID。 */
			String varianceLineId,
			/** 补扣片段ID。 */
			String adjustmentFlowId,
			/** 物料编码。 */
			String materialCode,
			/** 物料名称。 */
			String materialName,
			/** 原批次，可空。 */
			String batch,
			/** 原单位。 */
			String unit,
			/** 原件条码，可空。 */
			String barcode,
			/** 原件是否单件管理。 */
			boolean serialManaged,
			/** 片段已发出数量。 */
			String dispatchedQuantity,
			/** 片段累计已收数量。 */
			String receivedQuantity,
			/** 片段在途数量。 */
			String remainingQuantity) {
	}

	/**
	 * Detail业务合同。
	 */
	public record Detail(
			/** 差异单ID。 */
			String caseId,
			/** 差异单号。 */
			String caseNo,
			/** 调拨单ID。 */
			String billId,
			/** 调拨单号。 */
			String billNo,
			/** 源仓。 */
			String sourceWarehouseCode,
			/** 目标仓。 */
			String targetWarehouseCode,
			/** 当前案件状态。 */
			String status,
			/** 原发出请求号。 */
			String dispatchRequestId,
			/** 真实发出凭据。 */
			String dispatchEvidence,
			/** 真实发出时间。 */
			String dispatchTime,
			/** 不可互抵的补扣片段。 */
			List<Line> lines,
			/** 本案正式APPLIED回执，不包含命令全文。 */
			List<Receipt> receipts) {
	}

	/**
	 * DispatchResult业务合同。
	 */
	public record DispatchResult(
			/** 原差异单ID。 */String caseId,
			/** 本人服务端原号。 */String requestId,
			/** 真实办理人员ID。 */String actorId,
			/** NOT_FOUND/APPLIED/CANCELLED。 */String outcome,
			/** 原DISPOSE后状态；非APPLIED为空。 */String status,
			/** 原运输凭据；非APPLIED为空。 */String dispatchEvidence,
			/** 原发出时间；非APPLIED为空。 */String dispatchTime,
			/** 原终态时间；NOT_FOUND为空。 */String handledTime) {
	}

	/**
	 * 实际目标仓派出身份；结束资格不依赖今日业务开关。
	 */
	public record DispatchContext(
			/** 差异单ID。 */String caseId,
			/** 实际租户ID。 */String tenantId,
			/** 本人正ID。 */String actorId,
			/** 实际工厂。 */String factoryCode,
			/** 原目标仓。 */String targetWarehouseCode,
			/** 今日可派出。 */boolean canDispatch,
			/** 本人未受理号码可结束。 */boolean canEnd,
			/** ORD1人员UUIDv4。 */String nextRequestId) {
	}

	/**
	 * 发出命令保留原处置全部字段；新快照必须含版本与运输凭据。
	 */
	@lombok.Data
	@lombok.EqualsAndHashCode(callSuper = true)
	public static class DispatchCommand extends WmsTransferVarianceDTO.DispositionCommand {
		/**
		 * 协议版本，必须为1。
		 */
		private Integer v;
		/**
		 * 运输凭据，1至500字。
		 */
		private String dispatchEvidence;
	}
}
