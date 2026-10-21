package com.kitex.mom.mes.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class WipTimeoutNotifyResult {
    private String result;
    private String channels;
    private String recipientCodes;
    private String recipientNames;
    private String detail;
}
