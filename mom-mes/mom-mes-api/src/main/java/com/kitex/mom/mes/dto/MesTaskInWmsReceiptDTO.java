package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 成品入库原号的独立只读核账结果。
 * UNKNOWN不证明WMS未执行或允许换号；RECORDED不代表仓库实收，也不抹去后续红冲。
 */
@Schema(description = "成品入库原号核账，ID和数量均为精确文本")
public record MesTaskInWmsReceiptDTO(
        @Schema(description = "RECORDED、UNKNOWN或CANCELLED_BEFORE_DISPATCH（新协议确认未发送终态）") String state,
        @Schema(description = "永久请求号") String requestId,
        @Schema(description = "实际工厂编码") String factoryCode,
        @Schema(description = "实际生产工单主键") String taskId,
        @Schema(description = "原入库仓库编码") String warehouseCode,
        @Schema(description = "原入库数量，不扣除后续红冲") String quantity,
        @Schema(description = "MES永久回执主键；非RECORDED为空") String receiptId,
        @Schema(description = "当前红冲状态；不是原提交结果") String reversalStatus,
        @Schema(description = "新协议永久命令主键；旧协议或未登记为空") String commandId,
        @Schema(description = "永久发送边界状态；OUTBOUND_STARTED也不证明WMS已收到") String dispatchState) {
}
