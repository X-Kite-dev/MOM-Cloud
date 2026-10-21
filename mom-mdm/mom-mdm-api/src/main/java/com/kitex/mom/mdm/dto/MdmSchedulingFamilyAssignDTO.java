package com.kitex.mom.mdm.dto;

import lombok.Data;

import java.util.List;

@Data
public class MdmSchedulingFamilyAssignDTO {
    private String familyCode;
    private List<String> productCodes;
}
