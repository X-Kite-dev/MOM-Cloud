package com.kitex.mom.tpm.service;

import com.kitex.mom.tpm.dto.TpmDeviceAvailabilityQueryDTO;
import com.kitex.mom.tpm.dto.TpmDeviceUnavailableWindowDTO;

import java.util.List;

public interface TpmDeviceAvailabilityService {

    List<TpmDeviceUnavailableWindowDTO> unavailableWindows(TpmDeviceAvailabilityQueryDTO query);
}
