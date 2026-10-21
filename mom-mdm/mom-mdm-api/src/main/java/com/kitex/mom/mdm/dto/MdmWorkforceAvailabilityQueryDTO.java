package com.kitex.mom.mdm.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class MdmWorkforceAvailabilityQueryDTO {
    private List<String> employeeCodes;
    private List<String> processCodes;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}
