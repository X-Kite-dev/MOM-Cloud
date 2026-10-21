package com.kitex.mom.mdm.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MdmWorkforceWindowDTO {
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String shiftCode;
    private String shiftName;
    private String sourceType;
}
