package com.kitex.mom.plan.agent.dto;

import com.kitex.mom.plan.dto.PlanMrpShortageLineDTO;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 采购来料完成后对PLAN产品需求齐套状态的只读影响证据。
 */
public final class PlanIncomingAvailabilityImpactDTO {

    private PlanIncomingAvailabilityImpactDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        @NotBlank
        private String factoryCode;
        @NotEmpty
        private List<SourceReference> sources;
    }

    /**
     * PMS已核验的采购订单行与采购计划来源引用；PLAN仍须使用本地台账反向验证。
     */
    @Data
    @Accessors(chain = true)
    public static class SourceReference {
        private String purchaseOrderDetailId;
        private String purchasePlanNo;
        private String sourceIssueMonthId;
        private String materialCode;
    }

    @Data
    @Accessors(chain = true)
    public static class Evidence {
        private String toolCode;
        private String metricDefinition;
        private String factoryCode;
        private LocalDateTime observedAt;
        private Integer sourceCount;
        private Integer resolvedSourceCount;
        private List<DemandImpact> demands;
        private List<String> dataGaps;
        private List<String> limitations;
        private List<String> evidenceRefs;
        private String factFingerprint;
    }

    @Data
    @Accessors(chain = true)
    public static class DemandImpact {
        private String planDemandId;
        private String planDemandNo;
        private List<String> purchaseOrderDetailIds;
        private List<String> purchasePlanNos;
        private String saleOrderCode;
        private String materialAvailabilityStatus;
        private LocalDateTime materialAvailableTime;
        private Integer materialCount;
        private Integer shortageCount;
        private Integer reservationRiskCount;
        private Integer actionRequiredCount;
        private Integer supplyEvidenceGapCount;
        private String shortageFactFingerprint;
        private List<PlanMrpShortageLineDTO> shortageLines;
        /** 与本次收料同物料的 MRP 行以及其他缺料或数量待核的行数，截取明细前计数。 */
        private Integer materialBalanceCount;
        /** 当前需求的物料余额，优先列出本次收料涉及的物料，最多返回 100 行。 */
        private List<MaterialBalance> materialBalances;
    }

    /**
     * 已核对产品需求下的一条 MRP 记录。所有数量以原单位传递十进制文本，空值表示未记录。
     * @param mrpId MRP 原行主键
     * @param receiptMaterial 该需求经反查的收料物料与本行编码相同，仅用于定位
     * @param materialCode 物料编码
     * @param materialName 物料名称
     * @param materialUnit 原需求计量单位
     * @param warehouseCode 需求仓编码
     * @param grossDemandNum 毛需求量
     * @param allocatedNum 本轮 MRP 已分配库存量
     * @param remainingDemandNum 原行剩余需求量
     */
    public record MaterialBalance(String mrpId, boolean receiptMaterial, String materialCode,
                                  String materialName, String materialUnit, String warehouseCode,
                                  String grossDemandNum, String allocatedNum, String remainingDemandNum) {
    }
}
