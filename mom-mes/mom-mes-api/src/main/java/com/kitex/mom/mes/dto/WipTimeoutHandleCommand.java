package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "在制超时处置命令")
public class WipTimeoutHandleCommand {
    private Long id;
    private String requestId;
    private String rootCause;
    private String correctiveAction;
    private String disposition;
    private LocalDateTime expectedResolveTime;
    private String handleDesc;
}
