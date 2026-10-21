package com.kitex.mom.pms.service;

import com.kitex.mom.pms.dto.PmsAgentPurchaseCandidateQueryDTO;
import com.kitex.mom.pms.dto.PmsAgentPurchaseCommandDTO;
import com.kitex.mom.pms.dto.PmsAgentPurchaseOptionsDTO;
import com.kitex.mom.pms.dto.PmsAgentPurchaseReceiptDTO;

public interface PmsAgentPurchaseService {
    PmsAgentPurchaseOptionsDTO options(PmsAgentPurchaseCandidateQueryDTO query);

    PmsAgentPurchaseReceiptDTO execute(PmsAgentPurchaseCommandDTO command);

    PmsAgentPurchaseReceiptDTO receipt(String requestId, Long mrpId);
}
