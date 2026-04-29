package com.kitex.mom.common.barcode.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 条码校验记录
 *
 * @author kitex
 * @date 2023-09-01 14:45:49
 */
@Data
@Schema(description = "条码校验记录")
public class BarcodeCheckLogReq {

    private static final long serialVersionUID = 1L;

    /**
     * id
     */
    @Schema(description = "id")
    private Long id;

    /**
     * 结果
     */
    @Schema(description = "结果")
    private String result;

    /**
     * 条码
     */
    @Schema(description = "条码")
    private String barcode;

    /**
     * 编码
     */
    @Schema(description = "编码")
    private String templateCode;

    /**
     * 名称
     */
    @Schema(description = "名称")
    private String templateName;

    /**
     * 规则
     */
    @Schema(description = "规则")
    private String templateRule;

    /**
     * 数据
     */
    @Schema(description = "数据")
    private String jsonData;

}