package com.kitex.mom.tpm.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.tpm.Constants;
import com.kitex.mom.tpm.dto.TpmRepairRecoveryEvidence;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(contextId = "remoteTpmRepairRecoveryService", value = Constants.Name)
public interface RemoteTpmRepairRecoveryService {
    @GetMapping("/repair-verification/recovery-evidence/{id}")
    R<TpmRepairRecoveryEvidence> evidence(@PathVariable("id") Long id);
}
