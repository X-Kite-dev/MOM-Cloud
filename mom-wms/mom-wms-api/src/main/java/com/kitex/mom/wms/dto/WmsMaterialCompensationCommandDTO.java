package com.kitex.mom.wms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class WmsMaterialCompensationCommandDTO {
    private String requestId;
    private String action;
    private String reservationNo;
    private String requirementMatCode;
    private String actualMatCode;
}
