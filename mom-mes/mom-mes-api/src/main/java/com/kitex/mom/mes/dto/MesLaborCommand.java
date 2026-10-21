package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MesLaborCommand {
    private String requestId;
    private Long id;
    private Long segmentId;
    private Long correctedFromId;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime laborStart;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime laborEnd;
    private String expectedFingerprint;
    private String reason;
}
