package com.kitex.mom.wms.dto;

import lombok.Data;

import java.util.Set;

@Data
public class WmsLiveStockByProductCodeDto {

    private Set<String> productCodeSet;

    private String warehouseCode;

}
