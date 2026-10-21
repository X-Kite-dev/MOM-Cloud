package com.kitex.mom.mdm.service;

import com.kitex.mom.mdm.dto.*;
import com.kitex.mom.mdm.entity.MdmProcedureToolingRequirementEntity;
import com.kitex.mom.mdm.entity.MdmToolingEntity;

import java.util.List;

public interface MdmToolingService {
    List<MdmToolingEntity> listToolings(String toolingType, Boolean enabledOnly);

    Long saveTooling(MdmToolingEntity tooling);

    Boolean removeTooling(Long id);

    MdmToolingProfileDTO getProfile(String toolingCode);

    Boolean saveProfile(String toolingCode, MdmToolingProfileDTO profile);

    List<MdmProcedureToolingRequirementEntity> getRequirements(String routeCode, String processCode);

    Boolean saveRequirements(String routeCode, String processCode,
                             List<MdmProcedureToolingRequirementEntity> requirements);

    List<MdmProcedureToolingRequirementEntity> getNodeRequirements(String routeCode, String routeNodeCode);

    Boolean saveNodeRequirements(String routeCode, String routeNodeCode,
                                 List<MdmProcedureToolingRequirementEntity> requirements);

    List<MdmToolingCandidateDTO> availability(MdmToolingAvailabilityQueryDTO query);

    List<MdmToolingUsageResultDTO> consumeLife(MdmToolingUsageRequestDTO request);
}
