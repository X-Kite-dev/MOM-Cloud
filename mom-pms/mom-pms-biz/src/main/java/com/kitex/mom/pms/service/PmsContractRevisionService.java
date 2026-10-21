package com.kitex.mom.pms.service;

import com.kitex.mom.pms.dto.PmsContractRevisionDTO;
import com.kitex.mom.pms.entity.PmsContractRevisionRequestEntity;
import com.kitex.mom.pms.entity.PmsContractVersionEntity;

import java.util.List;

public interface PmsContractRevisionService {
    PmsContractRevisionDTO.Preview preview(PmsContractRevisionDTO.ContentCommand command);

    PmsContractRevisionDTO.Detail submit(PmsContractRevisionDTO.SubmitCommand command);

    PmsContractRevisionDTO.Detail review(PmsContractRevisionDTO.ReviewCommand command);

    PmsContractRevisionDTO.Detail withdraw(PmsContractRevisionDTO.WithdrawCommand command);

    PmsContractRevisionDTO.Detail detail(Long requestId);

    List<PmsContractRevisionRequestEntity> listByContract(Long contractId);

    List<PmsContractVersionEntity> versions(Long contractId);
}
