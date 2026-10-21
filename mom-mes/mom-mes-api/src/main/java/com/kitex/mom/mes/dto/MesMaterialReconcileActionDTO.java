package com.kitex.mom.mes.dto;

import lombok.Data;

@Data
public class MesMaterialReconcileActionDTO {
    private Long id;
    /**
     * CLAIM / RECHECK / IGNORE / REOPEN。
     */
    private String action;
    private String reason;
}
