package com.kitex.mom.mdm.dto;

import lombok.Data;

@Data
public class MdmChangeoverRuleActionDTO {
    private Long id;
    private String action;
    private String reason;
}
