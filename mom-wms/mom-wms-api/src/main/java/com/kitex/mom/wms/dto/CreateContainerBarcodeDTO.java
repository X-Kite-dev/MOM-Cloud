package com.kitex.mom.wms.dto;

import lombok.Data;

/**
 * 生成容器码参数
 *
 * @author kitex
 * @date 2025/1/2 15:54
 */
@Data
public class CreateContainerBarcodeDTO {

    /**
     * 容器类型
     */
    private String typeCode;

    /**
     * 仓库编码
     */
    private String warehouseCode;

    /**
     * 仓库名称
     */
    private String warehouseName;

    /**
     * 数量
     */
    private Integer count;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    private String factoryCode;

}
