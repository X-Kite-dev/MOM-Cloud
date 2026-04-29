package com.kitex.mom.common.barcode.dto.req;

import lombok.Data;

@Data
public class PrintDeviceReq {

    private Long id;

    /**
     * 编码
     */
    private String code;

    /**
     * 名称
     */
    private String name;

    /**
     * 描述
     */
    private String description;

    /**
     * 标签尺寸
     */
    private String labelSize;

    /**
     * 地址
     */
    private String address;

    /**
     * 关联终端
     */
    private String client;

}
