package com.kitex.mom.plan.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * MRP运算时冻结的单条BOM路径需求。聚合供应行仍按物料管理，但每个BOM出现位置单独留证。
 */
@Data
@Accessors(chain = true)
public class PlanBomRequirementPathDTO {
    private Long bomLineId;
    private Long bomId;
    private Long subitemBomId;
    private String bomPath;
    private String parentBomPath;
    private Integer bomLevel;
    private String rootProductCode;
    private String parentMaterialCode;
    private String materialCode;
    private String materialName;
    private String supplyType;
    private BigDecimal quantityPerParent;
    private BigDecimal grossDemandNum;
    private BigDecimal stockAllocatedNum;
    private BigDecimal netDemandNum;
    private LocalDate requirementDate;
    private LocalDate plannedReleaseDate;
    private Boolean hasChildBom;
    private Boolean childBomExploded;
}
