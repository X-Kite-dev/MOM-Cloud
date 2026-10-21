package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "在制超时事件取消命令")
public class WipTimeoutCancelCommand {
    private Long id;
    private String requestId;
    private String reason;
}
