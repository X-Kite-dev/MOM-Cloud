package com.kitex.mom.mes.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mes.Constants;
import com.kitex.mom.mes.dto.MesPlanExecutionEvidenceDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * MPS业务主线专用批量MES取证，不接模型或Agent目录。
 */
@FeignClient(contextId = "remoteMesPlanExecutionService", value = Constants.Name)
public interface RemoteMesPlanExecutionService {
    @InternalCall(userTokenRequired = true)
    @PostMapping("/mesPlanExecution/internal/query")
    R<MesPlanExecutionEvidenceDTO.Evidence> query(@RequestBody MesPlanExecutionEvidenceDTO.Query query);
}
