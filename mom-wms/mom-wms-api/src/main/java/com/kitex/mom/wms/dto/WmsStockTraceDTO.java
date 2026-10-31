package com.kitex.mom.wms.dto;

import com.kitex.mom.wms.entity.WmsInoutFlowEntity;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.entity.WmsMaterialMoveSiteEntity;
import com.kitex.mom.wms.entity.WmsPickingEventEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.List;

/**
 * WMS实物库存、库内移动、出入库流水及作业当前状态的证据查询。
 */
public final class WmsStockTraceDTO {
    private WmsStockTraceDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        @Schema(description = "精确库存行ID；与条码、物料编码及批次互斥，可查询已消耗库存的历史流水")
        private Long stockId;

        @Schema(description = "物料编码；按批次查询时必填，按条码查询时可选")
        private String materialCode;

        @Schema(description = "物料批次；与条码互斥")
        private String batchNo;

        @Schema(description = "条码或序列号；与批次互斥")
        private String barcode;

        @Schema(description = "货权类型；可缩小各查询方式的证据范围")
        private String stockOwnerType;

        @Schema(description = "归属方编码")
        private String stockOwnerCode;

        @Schema(description = "首次入库来源单号")
        private String sourceBusinessNo;

        @Schema(description = "每类证据最多返回条数，默认100、上限200")
        private Integer limit;
    }

    @Data
    @Accessors(chain = true)
    public static class Evidence {
        private String factoryCode;
        private Long sourceInBillDetailId;
        private List<WmsLiveStockEntity> currentStocks;
        private List<WmsInoutFlowEntity> flows;
        @Schema(description = "仅由本侧授权流水关联的组织交接，不赋予对方库存查询权")
        private List<OrganizationLink> organizationLinks;
        private List<WmsMaterialMoveSiteEntity> moves;
        @Schema(description = "逐次拣配作业证据；不代表正式出库，也不与出库流水叠加计数")
        private List<WmsPickingEventEntity> pickingEvents;
        @Schema(description = "尚未完成出库的拣货区当前状态，不是不可变操作履历")
        private List<PickingSnapshot> activePickings;
        @Schema(description = "按精确库存行关联的生产预留当前状态，不是不可变操作履历")
        private List<ReservationSnapshot> productionReservationDetails;
        @Schema(description = "按精确库存行关联的销售预留当前状态，不是不可变操作履历")
        private List<ReservationSnapshot> salesReservationDetails;
        private boolean stocksTruncated;
        private boolean flowsTruncated;
        private boolean movesTruncated;
        private boolean pickingEventsTruncated;
        private boolean activePickingsTruncated;
        private boolean productionReservationsTruncated;
        private boolean salesReservationsTruncated;
        private List<String> dataGaps;
        @Schema(description = "可机读的WMS证据质量；跨系统MES投料始终单独验证")
        private EvidenceQuality evidenceQuality;
    }

    public record OrganizationLink(Long flowId, Long billId, String billNo, String sourceCompanyCode,
                                   String sourceFactoryCode, String sourceWarehouseCode, String targetCompanyCode,
                                   String targetFactoryCode, String targetWarehouseCode, String action,
                                   BigDecimal quantity) { }

    public record EvidenceQuality(String wmsStatus, String crossSystemStatus, List<String> gapCodes) {
    }

    @Data
    @Accessors(chain = true)
    public static class PickingSnapshot {
        private Long id;
        private String billNo;
        private Long billDetailId;
        private Long sourceStockId;
        private Long sourceInBillDetailId;
        private String materialCode;
        private String matBatch;
        private String matBarcode;
        private String warehouseCode;
        private String siteCode;
        private Double matNum;
        private String stockOwnerType;
        private String stockOwnerCode;
        private String sourceBusinessNo;
        private Boolean serialManaged;
    }

    @Data
    @Accessors(chain = true)
    public static class ReservationSnapshot {
        private Long id;
        private Long liveStockId;
        private String reservationNo;
        private String materialCode;
        private String matBatch;
        private String warehouseCode;
        private String siteCode;
        private BigDecimal reservedNum;
        private BigDecimal consumedNum;
        private BigDecimal returnedNum;
        private BigDecimal releasedNum;
        private String status;
    }
}
