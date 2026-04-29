package com.kitex.mom.common.barcode.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 打印Req
 *
 * @author kitex
 * @date 2025-09-02 11:58:47
 */
@Data
@Schema(description = "打印Req")
public class PrintReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 表主键列表
     */
    @Schema(description = "表主键列表")
    private Long[] ids;

    /**
     * 模版编码
     */
    @Schema(description = "模版编码")
    private String templateCode;

}