package com.kitex.mom.mdm.service;

import com.kitex.mom.mdm.dto.MdmDurationCalibrationDTO;
import com.kitex.mom.mdm.entity.MdmDurationCalibrationPolicyEntity;
import com.kitex.mom.mdm.entity.MdmDurationCalibrationRunEntity;

public interface MdmDurationCalibrationService {
    Long savePolicy(MdmDurationCalibrationPolicyEntity policy);

    Boolean updatePolicy(MdmDurationCalibrationPolicyEntity policy);

    Boolean changePolicyStatus(MdmDurationCalibrationDTO.PolicyAction action);

    MdmDurationCalibrationRunEntity execute(MdmDurationCalibrationDTO.RunCommand command);

    Boolean updateProposal(MdmDurationCalibrationDTO.ProposalUpdate command);

    Boolean changeProposalStatus(MdmDurationCalibrationDTO.ProposalAction action);

    MdmDurationCalibrationDTO.Catalog catalog(MdmDurationCalibrationDTO.CatalogQuery query);
}
