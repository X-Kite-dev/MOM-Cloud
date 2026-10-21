package com.kitex.mom.mes.dto;

import lombok.Data;

@Data
public class ChangePointAcceptCommand {
    private Long id;
    private String requestId;
    private String remark;
}
