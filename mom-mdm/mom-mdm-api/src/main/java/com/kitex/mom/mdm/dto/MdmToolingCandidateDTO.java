package com.kitex.mom.mdm.dto;

import com.kitex.mom.mdm.entity.MdmProcedureToolingRequirementEntity;
import com.kitex.mom.mdm.entity.MdmToolingUnavailableWindowEntity;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
public class MdmToolingCandidateDTO {
    private Long requirementId;
    private String routeCode;
    /**
     * 此候选满足的具体路线节点；保留processCode供旧能力匹配消费者使用。
     */
    private String routeNodeCode;
    private String processCode;
    private MdmProcedureToolingRequirementEntity requirementSnapshot;
    private String toolingCode;
    private String toolingName;
    private String toolingType;
    private String toolingState;
    private String lifeUnit;
    private BigDecimal remainingLife;
    private BigDecimal warningRemainingLife;
    private Boolean calibrationRequired;
    private LocalDate calibrationValidTo;
    private LocalDate maintenanceDueDate;
    private List<String> compatibleDeviceCodes = new ArrayList<>();
    private Boolean qualified;
    private String qualificationReason;
    private List<MdmToolingUnavailableWindowEntity> unavailableWindows = new ArrayList<>();
}
