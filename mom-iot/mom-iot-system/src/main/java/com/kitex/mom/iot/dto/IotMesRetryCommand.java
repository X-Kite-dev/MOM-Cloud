package com.kitex.mom.iot.dto;
import lombok.Data;
@Data
public class IotMesRetryCommand {
    private String requestId;
    private String reason;
}
