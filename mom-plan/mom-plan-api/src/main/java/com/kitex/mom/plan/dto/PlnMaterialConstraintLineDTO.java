package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Accessors(chain = true)
@Schema(description = "排产物料约束明细快照")
public class PlnMaterialConstraintLineDTO {
    private Long mrpId;
    private Long bomLineId;
    private Long bomId;
    private Long subitemBomId;
    private String bomPath;
    private String parentBomPath;
    private Integer bomLevel;
    private String parentMaterialCode;
    private Boolean directToScheduledProduct;
    private Boolean hasChildBom;
    private String materialCode;
    private String materialName;
    private String demandWarehouseCode;
    private BigDecimal grossDemandNum;
    private BigDecimal stockAllocatedNum;
    private BigDecimal remainingDemandNum;
    private String readinessStatus;
    private String substituteStatus;
    private String supplyType;
    private Boolean supplyIssued;
    private LocalDate plannedReleaseDate;
    private LocalDate expectedAvailableDate;
    private LocalDate childConvergenceDate;
    private LocalDate effectiveAvailableDate;
    private LocalDateTime expectedAvailableTime;
    private LocalDateTime childConvergenceTime;
    private LocalDateTime effectiveAvailableTime;
    private String supplyTimePrecision;
    private String childConvergenceTimePrecision;
    private String effectiveTimePrecision;
    private String availabilityBasis;
    private String convergenceStatus;
    private List<String> childBomPaths;
    private String reservationNo;
    private String reservationStatus;
    private Boolean reservationHealthy;
    private String mainReservationNo;
    private String mainReservationStatus;
    private Boolean mainReservationHealthy;
    private String blockingReason;
}
