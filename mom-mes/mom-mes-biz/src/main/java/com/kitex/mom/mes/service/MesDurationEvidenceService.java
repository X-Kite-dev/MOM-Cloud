package com.kitex.mom.mes.service;

import com.kitex.mom.mes.dto.MesDurationEvidenceDTO;

public interface MesDurationEvidenceService {
    MesDurationEvidenceDTO.Bundle query(MesDurationEvidenceDTO.Query query);
}
