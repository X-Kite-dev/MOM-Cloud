package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 稳定投料的只读准备观察。所有主键和原单位数量均为文本，不把候选当作库存预留或执行许可。
 * 来源库位与实际接收工位独立；来源的NULL谱系不转换成空文本或本次BOM需求。
 */
public final class MesWarehouseFeedPreparationDTO {
    private MesWarehouseFeedPreparationDTO() {
    }

    /**
     * 编码是筛选条件，不包含客户端指定的租户、人员、接收工位或单位。
     */
    @Data
    @Schema(description = "投料准备筛选条件")
    public static class Query {
        /**
         * 必须明确的实际派工编码。
         */
        @Schema(description = "派工编码")
        private String arrangeCode;
        /**
         * 必须明确的实际投料物料编码。
         */
        @Schema(description = "投料物料编码")
        private String materialCode;
        /**
         * 可选本次BOM需求；不表示库存原始需求字段。
         */
        @Schema(description = "本次BOM需求物料筛选")
        private String requirementMaterialCode;
        /**
         * 可选批次；空文本表示不指定。
         */
        @Schema(description = "来源批次筛选")
        private String batchNo;
        /**
         * 可选库存条码，不能作为重试请求号。
         */
        @Schema(description = "来源条码筛选")
        private String barcode;
        /**
         * 可选来源仓库，不表示生产接收工位。
         */
        @Schema(description = "来源仓库筛选")
        private String sourceWarehouseCode;
        /**
         * 可选来源库位，不表示生产接收工位。
         */
        @Schema(description = "来源库位筛选")
        private String sourceSiteCode;
        /**
         * 可选原供应商。
         */
        @Schema(description = "来源供应商筛选")
        private String supplierCode;
        /**
         * 可选明确预留号；空文本不是仅查询未预留库存。
         */
        @Schema(description = "来源预留号筛选")
        private String reservationNo;
    }

    /**
     * 本次冻结BOM允许的需求绑定；多个需求须由操作员明确选择，不猜选第一项。
     */
    @Schema(description = "冻结BOM投料绑定选项")
    public record Binding(String requirementMaterialCode, String actualMaterialCode, String ratio,
                          boolean substitute, boolean mixedAllowed, String strategy) {
    }

    /**
     * 完整来源候选；stockId只作观察，V1命令按谱系和FIFO执行，不能承诺只扣选中的一行。
     */
    @Schema(description = "线边库来源观察")
    public record Source(String stockId, String quantity, String unit, String batchNo, String barcode,
                         String warehouseCode, String siteCode, String supplierCode, String reservationNo,
                         String sourceRequirementMaterialCode, String materialReceiptId, String sourceReportId,
                         String produceTaskCode, String inTime) {
    }

    /**
     * MES行读取采用独立一致快照，MDM计量单独读取；提交时必须重新核验实际状态。
     */
    @Schema(description = "稳定投料准备观察，非预留或执行许可")
    public record Observation(String version, MesWarehouseFeedCommandDTO.Context context,
                              String observedAt, String taskId, String taskCode, String taskStatus,
                              String arrangeId, String arrangeCode, String arrangeStatus, String produceTaskCode,
                              String receivingWorksiteCode, String materialId, String materialCode,
                              String unit, String materialUpdatedAt, List<Binding> bindings, List<Source> sources) {
    }
    /** 来源线索，不代表当前可用量；未关联领料工单时taskCode保持null。 */
    public record ReceiptSeed(String version, MesWarehouseFeedCommandDTO.Context context, String noticeId,
                              boolean taskBound, String taskCode, String materialCode, String batchNo, String barcode,
                              String supplierCode, String reservationNo) { }

    public record Candidate(String arrangeId, String arrangeCode, String taskCode, String routeName,
                            String worksiteCode, String workerName, String status) { }

    public record Candidates(String version, MesWarehouseFeedCommandDTO.Context context, long current, long size,
                             long total, List<Candidate> records) { }

}
