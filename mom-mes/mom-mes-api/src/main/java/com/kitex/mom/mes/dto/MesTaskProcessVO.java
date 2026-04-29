package com.kitex.mom.mes.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 工单实时进度
 *
 * @author kitex
 * since 2025-04-07
 */
@Data
public class MesTaskProcessVO {

    private String taskCode;

    private String status;

    private BigDecimal progress;

    private BigDecimal allNum;
}
