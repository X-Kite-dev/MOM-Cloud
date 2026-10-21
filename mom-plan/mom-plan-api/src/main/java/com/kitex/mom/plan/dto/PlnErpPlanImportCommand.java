package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** ERP 连接器向 PLAN 提交的规范正式计划，不接收任意 URL、凭据或可执行表达式。 */
@Data
@Schema(description = "ERP正式计划导入命令")
public class PlnErpPlanImportCommand {
    private String requestId;
    private String sourceSystemCode;
    private String sourceFactoryCode;
    private String sourcePlanNo;
    private String sourcePlanVersion;
    @Schema(description = "调用方按同一规范算法生成的计划内容SHA-256")
    private String sourceContentFingerprint;
    private List<Line> lines;

    /** ERP 计划中的单条自制、采购或外协需求。 */
    @Data
    public static class Line {
        private String lineNo;
        private String parentLineNo;
        private String sourceDemandNo;
        private String supplyType;
        @Schema(description = "ERP正式生产订单号；ERP_ORDER_REQUIRED模式的自制行必填")
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
    }
}
