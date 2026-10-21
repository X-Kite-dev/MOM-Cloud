package com.kitex.mom.qms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.qms.Constants;
import com.kitex.mom.qms.dto.QmsReworkCompletionDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(contextId = "remoteQmsReworkService", value = Constants.Name)
public interface RemoteQmsReworkService {
    @PostMapping("/qmsReworkOrder/mes-complete")
    R<Long> mesComplete(@RequestBody QmsReworkCompletionDTO command);
}
