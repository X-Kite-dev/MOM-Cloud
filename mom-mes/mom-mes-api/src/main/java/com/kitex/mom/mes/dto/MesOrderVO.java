package com.kitex.mom.mes.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 出货订单数据
 *
 * @author kitex
 * @since 2025-04-07
 */
@Data
public class MesOrderVO {

    private String name;

    private BigDecimal percentage;

    private BigDecimal orderNum;
}
