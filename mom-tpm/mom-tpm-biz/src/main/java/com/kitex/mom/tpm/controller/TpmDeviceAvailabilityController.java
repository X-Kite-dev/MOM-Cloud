package com.kitex.mom.tpm.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.tpm.dto.TpmDeviceAvailabilityQueryDTO;
import com.kitex.mom.tpm.dto.TpmDeviceUnavailableWindowDTO;
import com.kitex.mom.tpm.service.TpmDeviceAvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmDeviceAvailability")
@Tag(description = "tpmDeviceAvailability", name = "设备可用性")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmDeviceAvailabilityController {

    private final TpmDeviceAvailabilityService availabilityService;

    @Operation(summary = "查询设备不可用时间窗")
    @PostMapping("/windows")
    public R<List<TpmDeviceUnavailableWindowDTO>> unavailableWindows(
            @RequestBody TpmDeviceAvailabilityQueryDTO query) {
        return R.ok(availabilityService.unavailableWindows(query));
    }
}
