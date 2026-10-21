package com.kitex.mom.plan.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** MPS提交前的RCCP粗能力承诺预览。 */
public class PlnMpsCapacityDTO {

    @Data
    @Accessors(chain = true)
    public static class Preview {
        private String mode;
        private BigDecimal maxLoadRate;
        private String overallStatus;
        private List<Line> lines;
    }

    @Data
    @Accessors(chain = true)
    public static class Line {
        private Long demandId;
        private String demandNo;
        private Integer bucketNo;
        private String bucketKey;
        private String productCode;
        private BigDecimal planQuantity;
        private LocalDate plannedDate;
        private String resourceCode;
        private String resourceName;
        private String capacityBasis;
        /** 产品数量对应的纯加工分钟。 */
        private BigDecimal processingMinutes;
        /** 相邻产品族切换占用分钟。 */
        private BigDecimal changeoverMinutes;
        private String fromFamilyCode;
        private String toFamilyCode;
        private String changeoverRuleNo;
        private Integer changeoverRuleVersion;
        private BigDecimal demandMinutes;
        private BigDecimal committedMinutes;
        private BigDecimal availableMinutes;
        private BigDecimal loadRate;
        private String status;
        private Boolean verified;
        private String message;
        private String evidenceFingerprint;
    }
}
