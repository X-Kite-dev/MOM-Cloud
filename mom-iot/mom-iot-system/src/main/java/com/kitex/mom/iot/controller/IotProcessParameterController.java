package com.kitex.mom.iot.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.iot.agent.dto.IotProcessTelemetryDTO;
import com.kitex.mom.iot.agent.service.IotProcessTelemetryQueryService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 普通生产页面的只读参数对比；复用IoT领域取证，不依赖Agent或设备控制。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/process-parameters")
public class IotProcessParameterController {
    private final IotProcessTelemetryQueryService queryService;

    @GetMapping("/comparison")
    @PreAuthorize(
            "@pms.hasPermission('mesTask:show') and @pms.hasPermission('mesProduceTask:show') and"
                    + " @pms.hasPermission('iot_device_view')")
    public R<IotProcessTelemetryDTO.Evidence> comparison(
            @Valid IotProcessTelemetryDTO.Query query) {
        return R.ok(queryService.query(query));
    }
}
