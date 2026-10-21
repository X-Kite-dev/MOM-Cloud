package com.kitex.mom.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 成品实收入库红冲的申请、撤回、重提和复核命令。
 */
@Data
@Schema(description = "成品实收入库红冲操作命令")
public class WmsFinishedGoodsReceiptCorrectionCommand {
    @Schema(description = "WMS成品入库通知ID；提交申请时必填")
    private Long noticeId;
    @Schema(description = "操作永久幂等号")
    private String requestId;
    @Schema(description = "申请或撤回原因")
    private String reason;
    @Schema(description = "复核驳回原因")
    private String rejectReason;
    @Schema(description = "QMS质量判定逆向授权ID；来源使用具体QMS任务时必填")
    private Long qmsDecisionReversalId;
    @Schema(description = "QMS质量判定逆向授权码；来源使用具体QMS任务时必填")
    private String qmsDecisionAuthorizationCode;

    @Schema(description = "本次红冲选择的精确库存行及数量；为空仅兼容原整单全量申请")
    private List<StockSelection> stockSelections = new ArrayList<>();

    /**
     * 一条库存只能出现一次；带条码的单件库存必须选择整行数量。
     */
    @Data
    @Schema(description = "成品红冲库存行选择")
    public static class StockSelection {
        @Schema(description = "当前实时库存ID")
        private Long stockId;
        @Schema(description = "本次从该库存行红冲的数量")
        private BigDecimal quantity;
    }
}
