package com.kitex.mom.mdm.service;

import com.kitex.mom.mdm.dto.MdmEmployeeWorkforceProfileDTO;
import com.kitex.mom.mdm.dto.MdmWorkforceAvailabilityQueryDTO;
import com.kitex.mom.mdm.dto.MdmWorkforceCandidateDTO;
import com.kitex.mom.mdm.entity.MdmProcedureSkillRequirementEntity;
import com.kitex.mom.mdm.entity.MdmSkillEntity;

import java.util.List;

public interface MdmWorkforceService {
    List<MdmSkillEntity> listSkills(Boolean enabledOnly);

    Long saveSkill(MdmSkillEntity skill);

    Boolean removeSkill(Long id);

    MdmEmployeeWorkforceProfileDTO getEmployeeProfile(String employeeCode);

    Boolean saveEmployeeProfile(MdmEmployeeWorkforceProfileDTO profile);

    List<MdmProcedureSkillRequirementEntity> getProcedureRequirements(String processCode);

    Boolean saveProcedureRequirements(String processCode, List<MdmProcedureSkillRequirementEntity> requirements);

    List<MdmWorkforceCandidateDTO> availability(MdmWorkforceAvailabilityQueryDTO query);
}
