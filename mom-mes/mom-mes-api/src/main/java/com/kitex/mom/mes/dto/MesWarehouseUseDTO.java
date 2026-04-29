package com.kitex.mom.mes.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * 消耗
 */
@Data
@Accessors(chain = true)
public class MesWarehouseUseDTO {

    private String arrangeCode;

    private BigDecimal reportNum;

    private String routeCode;

    private String processRouteCode;

    private String snapshot;

}
