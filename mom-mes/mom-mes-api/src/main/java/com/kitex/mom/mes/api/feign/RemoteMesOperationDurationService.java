package com.kitex.mom.mes.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mes.Constants;
import com.kitex.mom.mes.dto.MesOperationDurationDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * APS回评精确生产段取证；不能匿名或扩展查询到任意时间窗。
 */
@FeignClient(contextId = "remoteMesOperationDurationService", value = Constants.Name)
public interface RemoteMesOperationDurationService {
    @InternalCall(userTokenRequired = true)
    @PostMapping("/mesOperationDuration/internal/query")
    R<MesOperationDurationDTO.Evidence> query(@RequestBody MesOperationDurationDTO.Query query);
}
