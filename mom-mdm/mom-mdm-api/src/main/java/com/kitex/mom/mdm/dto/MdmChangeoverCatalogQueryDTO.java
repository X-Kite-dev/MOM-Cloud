package com.kitex.mom.mdm.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class MdmChangeoverCatalogQueryDTO {
    private List<String> productCodes;
    private String factoryCode;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}
