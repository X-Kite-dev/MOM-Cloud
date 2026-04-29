package com.kitex.mom.mes.dto;

import lombok.Data;

/**
 * 线边库余料退料审批提交DTO
 */
@Data
public class MesWarehouseFeedJudgeDTO {

    private Long id;

    private String opinion;

    private Boolean pass;
} 