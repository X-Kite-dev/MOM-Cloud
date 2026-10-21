package com.kitex.mom.mdm.dto;

import com.kitex.mom.mdm.entity.MdmEmployeeSkillEntity;
import com.kitex.mom.mdm.entity.MdmProcedureSkillRequirementEntity;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MdmWorkforceCandidateDTO {
    private String employeeCode;
    private String employeeName;
    private String processCode;
    private Boolean employeeActive;
    private Boolean qualified;
    private String qualificationReason;
    private List<MdmProcedureSkillRequirementEntity> requirementSnapshot = new ArrayList<>();
    /**
     * 当前员工在查询区间内可用于组合班组的技能明细。
     */
    private List<MdmEmployeeSkillEntity> skillSnapshot = new ArrayList<>();
    /**
     * 已满足等级、持证及有效期交集要求的技能编码；多人排产按此进行能力覆盖组合。
     */
    private List<String> matchedSkillCodes = new ArrayList<>();
    /**
     * 员工所属启用班组，用于APS优先形成同班组执行单元。
     */
    private List<String> teamCodes = new ArrayList<>();
    private List<String> teamNames = new ArrayList<>();
    private List<MdmWorkforceWindowDTO> availableWindows = new ArrayList<>();
}
