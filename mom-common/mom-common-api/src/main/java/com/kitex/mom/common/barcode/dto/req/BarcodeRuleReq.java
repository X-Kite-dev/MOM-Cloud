package com.kitex.mom.common.barcode.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 条码核心表
 *
 * @author kitex
 * @date 2023-09-01 14:45:01
 */
@Data
@Schema(description = "条码核心表")
public class BarcodeRuleReq {

    private static final long serialVersionUID = 1L;

    /**
     * id
     */
    @Schema(description = "id")
    private Long id;

    /**
     * 条码名称
     */
    @Schema(description = "条码名称")
    private String name;

    /**
     * 条码编码
     */
    @Schema(description = "条码编码")
    private String code;

    /**
     * 正则
     */
    @Schema(description = "正则")
    private String regular;

    /**
     * 业务字段
     */
    @Schema(description = "业务字段")
    @NotEmpty(message = "业务字段不能为空")
    private List<BarcodeBusinessFieldReq> businessReqList;

    /**
     * 插件路径
     */
    @Schema(description = "插件路径")
    private String pluginPath;

    /**
     * 打印模板code
     */
    @Schema(description = "打印模板code")
    private String printTemplateCode;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    private String factoryCode;

}