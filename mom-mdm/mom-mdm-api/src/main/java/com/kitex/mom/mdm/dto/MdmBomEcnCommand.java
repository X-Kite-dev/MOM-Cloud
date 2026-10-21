package com.kitex.mom.mdm.dto;

import lombok.Data;

@Data
public class MdmBomEcnCommand {
    private String requestId;
    private Long ecnId;
    private Long targetBomId;
    private String reason;
    private String disposition;
    private String expectedFingerprint;
    private String roleCode;
}
