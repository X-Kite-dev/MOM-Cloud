package com.kitex.mom.iot.agent.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.iot.agent.dto.IotProcessTelemetryDTO;
import com.kitex.mom.iot.agent.service.IotProcessTelemetryQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** IoT 面向 Agent 的受控只读语义查询。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/iotAgentQuery")
public class IotAgentQueryController {

    private final IotProcessTelemetryQueryService processTelemetryService;

    @Inner
    @PostMapping("/internal/process-telemetry")
    @PreAuthorize("@pms.hasPermission('iot_device_view')")
    public R<IotProcessTelemetryDTO.Evidence> processTelemetry(
            @Valid @RequestBody IotProcessTelemetryDTO.Query query) {
        return R.ok(processTelemetryService.query(query));
    }
}
