package com.kitex.mom.pms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Accessors(chain = true)
public class PmsAgentSupplyEvidenceDTO {
    private String orderNo;
    private String pmsPlanNo;
    private String materialCode;
    private String materialName;
    private String supplyType;
    private String planStatus;
    private BigDecimal plannedNum;
    private BigDecimal orderedNum;
    private BigDecimal arrivedNum;
    /**
     * 已通过采购审批且尚未到货的数量；齐套覆盖只允许使用该口径。
     */
    private BigDecimal openApprovedOrderedNum;
    /**
     * 草稿或审批中的采购订单数量；用于防止Agent重复补单，但不能用于承诺齐料。
     */
    private BigDecimal pendingApprovalNum;
    private LocalDate plannedDeliveryDate;
    /**
     * 采购计划自身仅维护日期时按日末保守换算，不替代供应商正式承诺时刻。
     */
    private LocalDateTime plannedDeliveryTime;
    private String plannedDeliveryTimePrecision;
    private BigDecimal confirmedCommittedNum;
    /**
     * NONE/PARTIAL/FULL/RECEIVED
     */
    private String commitmentCoverageStatus;
    private List<PmsSupplyCommitmentEvidenceDTO> commitments;
    private LocalDateTime lastArrivalTime;
    private String factoryCode;
}
