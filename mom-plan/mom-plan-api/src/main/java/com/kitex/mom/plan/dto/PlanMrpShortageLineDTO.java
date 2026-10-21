package com.kitex.mom.plan.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Accessors(chain = true)
public class PlanMrpShortageLineDTO {
    private Long mrpId;
    private String planDemandNo;
    private String saleOrderCode;
    private String materialCode;
    private String materialName;
    private String materialUnit;
    private String materialSpec;
    private String demandWarehouseCode;
    private String demandWarehouseName;
    private BigDecimal grossDemandNum;
    private BigDecimal allocatedNum;
    private String mrpRunNo;
    private Integer inventoryAllocationPriority;
    private Integer inventoryAllocationSequence;
    private BigDecimal inventoryPeggedNum;
    private String inventoryPeggingStatus;
    private String inventoryPeggingFingerprint;
    private BigDecimal shortageNum;
    private String readinessStatus;
    private String substituteStatus;
    private String substituteSuggestion;
    private String reservationNo;
    private String reservationStatus;
    private Boolean reservationHealthy;
    private String reservationRisk;
    private String mainReservationNo;
    private String mainReservationStatus;
    private Boolean mainReservationHealthy;
    private String mainReservationRisk;
    private LocalDate demandDate;
    private LocalDate plannedReleaseDate;
    private LocalDate expectedSupplyDate;
    private LocalDateTime expectedSupplyTime;
    /** MINUTE、DATE_END或INVENTORY_NOW。 */
    private String supplyTimePrecision;
    private String supplyDateBasis;
    private String supplyType;
    private String supplyPlanStatus;
    private String supplyPlanNos;
    private BigDecimal plannedSupplyNum;
    private BigDecimal orderedSupplyNum;
    private BigDecimal arrivedSupplyNum;
    private BigDecimal openOrderedSupplyNum;
    private BigDecimal pendingApprovalSupplyNum;
    private BigDecimal confirmedSupplyNum;
    private BigDecimal overdueCommittedSupplyNum;
    private String supplierCommitmentStatus;
    private String supplierCommitmentNos;
    private String operationImpactStatus;
    private Boolean operationImpactEvidenceComplete;
    private Integer impactedOperationCount;
    private String firstImpactedProcessCode;
    private String firstImpactedProcessName;
    private LocalDateTime firstImpactedStartTime;
    private List<PlanMrpImpactedOperationDTO> impactedOperations;
    private Boolean supplyEvidenceComplete;
    private String crossWarehouseTransferStatus;
    private Boolean crossWarehouseTransferEvidenceComplete;
    private BigDecimal targetInTransitTransferNum;
    private BigDecimal appliedInTransitTransferNum;
    private BigDecimal suggestedTransferNum;
    private BigDecimal transferUncoveredNum;
    private String transferEvidenceFingerprint;
    private List<PlanMrpTransferSuggestionDTO> transferSuggestions;
    private Integer minOrderQuantity;
    private Integer lotSize;
    private BigDecimal purchaseUncoveredNum;
    private BigDecimal recommendedPurchaseNum;
    private Boolean purchaseEvidenceComplete;
    private String purchaseEvidenceStatus;
    private String purchaseEvidenceGap;
    private List<PlanMrpPurchaseCandidateDTO> purchaseCandidates;
    private String recommendedActionCode;
    private String recommendedAction;
}
