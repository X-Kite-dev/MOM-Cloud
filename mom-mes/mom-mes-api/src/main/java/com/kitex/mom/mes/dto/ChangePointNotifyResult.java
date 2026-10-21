package com.kitex.mom.mes.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ChangePointNotifyResult {
    private String recipientCodes;
    private String recipientNames;
    private String channels;
    private String result;
    private String detail;
}
