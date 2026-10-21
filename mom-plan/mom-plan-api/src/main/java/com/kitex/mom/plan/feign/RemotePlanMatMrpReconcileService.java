package com.kitex.mom.plan.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.Constants;
import com.kitex.mom.plan.dto.PlanMatMrpReconcileEvidenceDTO;
import com.kitex.mom.plan.dto.PlanMatMrpReconcileQueryDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@FeignClient(contextId = "remotePlanMatMrpReconcileService", value = Constants.Name)
public interface RemotePlanMatMrpReconcileService {
    @PostMapping("/planMatMrp/reconcile/evidence")
    R<List<PlanMatMrpReconcileEvidenceDTO>> evidence(@RequestBody PlanMatMrpReconcileQueryDTO query);
}
