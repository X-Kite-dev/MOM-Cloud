package com.kitex.mom.mdm.dto;

import com.kitex.mom.mdm.entity.MdmToolingDeviceCompatibilityEntity;
import com.kitex.mom.mdm.entity.MdmToolingEntity;
import com.kitex.mom.mdm.entity.MdmToolingUnavailableWindowEntity;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MdmToolingProfileDTO {
    private MdmToolingEntity tooling;
    private List<MdmToolingDeviceCompatibilityEntity> compatibleDevices = new ArrayList<>();
    private List<MdmToolingUnavailableWindowEntity> unavailableWindows = new ArrayList<>();
}
