package com.kitex.mom.pms.agent.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * PMS 活动采购订单、到货和正式供应商承诺风险模型，供智能体只读问数使用。
 */
public final class PmsProcurementRiskDTO {

    private PmsProcurementRiskDTO() {
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "PMS 采购履约风险受控查询")
    public static class Query {
        @NotBlank
        private String factoryCode;
        /** 精确订单入口使用文本主键，避免浏览器长整数精度损失。 */
        @Pattern(regexp = "[1-9][0-9]{0,18}")
        private String orderId;
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._/-]{1,99}")
        @Size(max = 100)
        private String orderNo;
        @Min(1)
        @Max(90)
        private Integer horizonDays = 7;
        @Min(1)
        @Max(50)
        private Integer limit = 20;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "PMS 采购履约风险权威证据")
    public static class Evidence {
        private String toolCode;
        private String metricDefinition;
        private String factoryCode;
        private Boolean orderScoped;
        private String orderId;
        private String orderNo;
        private String orderStatus;
        private LocalDateTime observedAt;
        private LocalDateTime horizonEnd;
        private Summary summary;
        private List<RiskLine> riskLines = new ArrayList<>();
        /** 固定口径边界不等同于数据缺失，供模型和用户理解结论适用范围。 */
        private List<String> limitations = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
        private String factFingerprint;
        private Boolean truncated;
    }

    @Data
    @Accessors(chain = true)
    public static class Summary {
        private Long activeOrderCount;
        private Long openLineCount;
        private Long pendingApprovalOrderCount;
        private Long overdueCommitmentLineCount;
        private Long dueSoonLineCount;
        private Long uncommittedGapLineCount;
        private Long internalDueDatePassedLineCount;
        private Long commitmentExcessLineCount;
        private Long missingUnitLineCount;
    }

    @Data
    @Accessors(chain = true)
    public static class RiskLine {
        /** 所属采购订单主键，跨端精确定位原单。 */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long orderId;
        /** 原采购订单行主键。同物料重复下单时分别办理承诺。 */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long orderDetailId;
        /** 原订单行上的采购计划号。未关联计划的采购行仍可按行主键办理。 */
        @Schema(description = "原采购计划号")
        private String purchasePlanNo;
        private String orderNo;
        private String orderStatus;
        private String supplierCode;
        private String supplierName;
        private String materialCode;
        private String materialName;
        private String materialUnit;
        private BigDecimal orderNum;
        private BigDecimal arrivalNum;
        private BigDecimal cancelledNum;
        private BigDecimal outstandingNum;
        private BigDecimal committedRemainingNum;
        private BigDecimal overdueCommitmentNum;
        private BigDecimal dueSoonCommitmentNum;
        private BigDecimal uncommittedGapNum;
        private LocalDate internalDeliveryDate;
        private LocalDateTime nextCommittedDeliveryTime;
        private List<String> riskTypes = new ArrayList<>();
    }
}
