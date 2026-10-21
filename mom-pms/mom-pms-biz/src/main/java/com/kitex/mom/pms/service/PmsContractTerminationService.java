package com.kitex.mom.pms.service;

import com.kitex.mom.pms.dto.PmsContractTerminationDTO;
import com.kitex.mom.pms.entity.PmsContractTerminationRequestEntity;

import java.util.List;

public interface PmsContractTerminationService {
    PmsContractTerminationDTO.Preview preview(Long contractId);

    PmsContractTerminationDTO.Detail submit(PmsContractTerminationDTO.SubmitCommand command);

    PmsContractTerminationDTO.Detail review(PmsContractTerminationDTO.ReviewCommand command);

    PmsContractTerminationDTO.Detail detail(Long requestId);

    List<PmsContractTerminationRequestEntity> listByContract(Long contractId);
}
