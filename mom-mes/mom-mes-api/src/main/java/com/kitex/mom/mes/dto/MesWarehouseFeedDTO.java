package com.kitex.mom.mes.dto;

import com.kitex.mom.mes.entity.MesMaterialReturnOperationEntity;
import com.kitex.mom.mes.entity.MesWarehouseStockFeedJudgeEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.List;

/**
 * 生产退料
 *
 * @author kitex
 * @since 2025-04-09
 */
@Data
public class MesWarehouseFeedDTO {
    @Schema(description = "线边库存来源领料/签收单号")
    private String billNo;

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String matCode;

    @Schema(description = "WMS物料预留单号")
    private String reservationNo;

    @Schema(description = "BOM需求物料编码")
    private String requirementMatCode;

    @Schema(description = "BOM需求物料名称")
    private String requirementMatName;

    @Schema(description = "是否为替代料")
    private Boolean substituteFlag;

    @Schema(description = "替代比例")
    private BigDecimal substituteRatio;

    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String matName;

    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 批次号
     */
    @Schema(description = "批次号")
    private String matBatch;

    /**
     * 单位
     */
    @Schema(description = "单位")
    private String matUnit;


    /**
     * 供应商编码
     */
    @Schema(description = "供应商编码")
    private String supplierCode;

    /**
     * 供应商名称
     */
    @Schema(description = "供应商名称")
    private String supplierName;

    /**
     * 仓库编码
     */
    @Schema(description = "仓库编码")
    private String warehouseCode;

    /**
     * 仓库名称
     */
    @Schema(description = "仓库名称")
    private String warehouseName;

    @Schema(description = "线边库位编码")
    private String siteCode;

    @Schema(description = "线边库位名称")
    private String siteName;

    /**
     * 数量
     */
    @Schema(description = "数量")
    private BigDecimal num;

    private String judgeStatus;

    private String taskCode;

    private String reason;

    private BigDecimal backNum;

    private String factoryCode;

    @Schema(description = "提交永久幂等请求号")
    private String requestId;

    @Schema(description = "预览取得的来源事实指纹")
    private String expectedEvidenceFingerprint;

    /**
     * 受控退料资格、数量和冻结证据。
     */
    @Data
    @Accessors(chain = true)
    public static class Evidence {
        private MesWarehouseFeedDTO source;
        private String evidenceFingerprint;
        private String evidenceSnapshot;
        private BigDecimal availableQuantity;
        private Boolean eligible;
        private List<String> blockers;
        private MesWarehouseStockFeedJudgeEntity activeRequest;
    }

    /**
     * 退料申请、实时事实和不可变操作履历。
     */
    @Data
    @Accessors(chain = true)
    public static class Detail {
        private MesWarehouseStockFeedJudgeEntity request;
        private Evidence currentEvidence;
        private List<MesMaterialReturnOperationEntity> operations;
    }

    /** 本人退料申请的认证上下文，不作为客户端指定组织的入口。 */
    public record SubmissionContext(String version, String tenantId, String factoryCode, String actorUserId, String actorCode) { }
    /** 查询无记录仅代表当前观察，不代表旧请求已终止。 */
    public record Submission(String version, SubmissionContext context, String requestId, MesWarehouseStockFeedJudgeEntity request) { }

}
