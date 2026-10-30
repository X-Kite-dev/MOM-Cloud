package com.kitex.mom.plan.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.Constants;
import com.kitex.mom.plan.dto.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(contextId = "remotePlanMrpAgentService", value = Constants.Name)
public interface RemotePlanMrpAgentService {
    @InternalCall(userTokenRequired = true)
    @PostMapping("/planMatMrp/agent/shortage-analysis")
    R<PlanMrpShortageAnalysisDTO> shortageAnalysis(@RequestBody PlanMrpShortageQueryDTO query);

    @PostMapping("/planMatMrp/agent/substitute-decision")
    R<Boolean> substituteDecision(@RequestBody PlanAgentSubstituteDecisionDTO command);

    @PostMapping("/planMatMrp/agent/transfer-decision")
    R<PlanAgentTransferReceiptDTO> transferDecision(@RequestBody PlanAgentTransferDecisionDTO command);

    @PostMapping("/planMatMrp/agent/purchase-order")
    R<PlanAgentPurchaseReceiptDTO> purchaseOrder(@RequestBody PlanAgentPurchaseOrderDTO command);

    @InternalCall
    @PostMapping("/planMatMrp/internal/agent-purchase/rejection")
    R<PlanAgentPurchaseRejectionDTO.Receipt> rejectPurchaseOrder(
            @RequestBody PlanAgentPurchaseRejectionDTO.Command command);

    @InternalCall
    @GetMapping("/plnShortageReplan/internal/{caseId}")
    R<PlanShortageLocalReplanDTO.Proposal> shortageReplanDetail(@PathVariable("caseId") Long caseId);

    @PostMapping("/plnShortageReplan/agent/publish")
    R<PlanShortageLocalReplanDTO.PublishReceipt> publishShortageReplan(
            @RequestBody PlanShortageLocalReplanDTO.PublishCommand command);
}
