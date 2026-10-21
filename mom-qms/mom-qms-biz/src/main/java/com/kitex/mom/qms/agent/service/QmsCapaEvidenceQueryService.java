package com.kitex.mom.qms.agent.service;

import com.kitex.mom.qms.agent.dto.QmsCapaEvidenceDTO;

/**
 * CAPA及来源NCR的精确组织范围、全周期只读取证。
 */
public interface QmsCapaEvidenceQueryService {
    QmsCapaEvidenceDTO.Evidence query(Long capaId);
}
