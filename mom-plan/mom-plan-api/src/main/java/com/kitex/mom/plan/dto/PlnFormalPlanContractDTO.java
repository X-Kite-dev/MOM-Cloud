package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 对下游公开的已核验正式计划合同；所有雪花主键按字符串输出。 */
@Data
@Schema(description = "正式计划合同")
public class PlnFormalPlanContractDTO {
    private String contractNo;
    private Integer contractVersion;
    private String sourceSystemCode;
    private String sourceFactoryCode;
    private String sourcePlanNo;
    private String sourcePlanVersion;
    private String planningPolicyId;
    private Integer planningPolicyVersion;
    private String planningPolicyFingerprint;
    private String sourceContentFingerprint;
    private String verificationStatus;
    private String contractStatus;
    private LocalDateTime verifiedTime;
    private List<Line> lines;

    /** 合同明细保留父子行关系，支持多层自制件和其采购/外协需求。 */
    @Data
    public static class Line {
        private String id;
        private String lineNo;
        private String parentLineNo;
        private String sourceDemandNo;
        private String supplyType;
        private String erpProductionOrderNo;
        private String productCode;
        private String productName;
        private BigDecimal plannedQuantity;
        private String unitCode;
        private LocalDate demandDate;
        private LocalDateTime committedStartTime;
        private LocalDateTime committedEndTime;
        private String bomVersion;
        private String routeVersion;
        private String substitutePolicyVersion;
        private String contentFingerprint;
    }
}
