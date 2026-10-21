package com.kitex.mom.mdm.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MdmToolingUsageRequestDTO {
    private String requestId;
    private String businessType;
    private String businessCode;
    private String remark;
    private List<MdmToolingUsageLineDTO> lines = new ArrayList<>();
}
