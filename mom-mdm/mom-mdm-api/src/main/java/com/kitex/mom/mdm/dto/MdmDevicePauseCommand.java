package com.kitex.mom.mdm.dto;

import lombok.Data;

/**
 * 仅转换业务运行状态；不发送PLC指令，也不解除维修/保养/质量封锁。
 */
@Data
public class MdmDevicePauseCommand {
    private String requestId;
    private String deviceCode;
    private String moldCode;
    private String reason;
}
