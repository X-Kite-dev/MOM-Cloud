package com.kitex.mom.qms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.qms.Constants;
import com.kitex.mom.qms.dto.QmsMesCaseDecision;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(contextId = "remoteQmsMesCaseDecisionService", value = Constants.Name)
public interface RemoteQmsMesCaseDecisionService {
    @InternalCall(userTokenRequired = true)
    @GetMapping("/bad/internal/mes-decision/{id}")
    R<QmsMesCaseDecision> decision(@PathVariable("id") Long id);
}
