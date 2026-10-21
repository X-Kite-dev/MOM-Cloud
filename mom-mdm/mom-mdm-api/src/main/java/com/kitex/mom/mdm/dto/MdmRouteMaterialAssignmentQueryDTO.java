package com.kitex.mom.mdm.dto;

import lombok.Data;

import java.util.Set;

@Data
public class MdmRouteMaterialAssignmentQueryDTO {
    private String factoryCode;
    private Set<String> routeCodes;
    private Set<String> materialCodes;
}
