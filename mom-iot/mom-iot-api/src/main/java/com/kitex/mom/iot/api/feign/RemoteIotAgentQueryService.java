package com.kitex.mom.iot.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.iot.Constants;
import com.kitex.mom.iot.agent.dto.IotProcessTelemetryDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** IoT 面向智能体的受控只读语义查询。 */
@FeignClient(contextId = "remoteIotAgentQueryService", value = Constants.Name)
public interface RemoteIotAgentQueryService {

    /** 查询指定工单实际生产区间内、归属已核验的设备属性采样证据。 */
    @InternalCall(userTokenRequired = true)
    @PostMapping("/iotAgentQuery/internal/process-telemetry")
    R<IotProcessTelemetryDTO.Evidence> processTelemetry(
            @RequestBody IotProcessTelemetryDTO.Query query);
}
