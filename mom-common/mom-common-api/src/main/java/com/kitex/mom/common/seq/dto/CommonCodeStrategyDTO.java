package com.kitex.mom.common.seq.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 编码规则DTO
 *
 * @author claude
 * @date 2025-08-11
 */
@Data
@Schema(description = "编码规则DTO")
public class CommonCodeStrategyDTO {

    /**
     * 主键ID
     */
    @Schema(description = "主键ID")
    private Long id;

    /**
     * 父级编码
     */
    @Schema(description = "父级编码")
    private String parentCode;

    /**
     * 规则编码
     */
    @Schema(description = "规则编码")
    @NotBlank(message = "规则编码不能为空")
    private String code;

    /**
     * 描述
     */
    @Schema(description = "描述")
    @NotBlank(message = "描述不能为空")
    private String description;

    /**
     * 规则类型（编码，标签码）
     */
    @Schema(description = "规则类型（编码，标签码）")
    @NotBlank(message = "规则类型不能为空")
    private String type;

    /**
     * 标签码类型（包装箱码，客户码，内包装码，样本码，SN）
     */
    @Schema(description = "标签码类型（包装箱码，客户码，内包装码，样本码，SN）")
    private String labelType;

    /**
     * 长度
     */
    @Schema(description = "长度")
    @NotNull(message = "长度不能为空")
    private Integer length;

    /**
     * 是否校验长度
     */
    @Schema(description = "是否校验长度")
    @NotNull(message = "是否校验长度不能为空")
    private Boolean checkLength;

    /**
     * 是否默认
     */
    @Schema(description = "是否默认")
    @NotNull(message = "是否默认不能为空")
    private Boolean isDefault;

    /**
     * 段数
     */
    @Schema(description = "段数")
    @NotNull(message = "段数不能为空")
    private Integer segmentNum;

    /**
     * 解析实体类
     */
    @Schema(description = "解析实体类")
    private String parseEntity;

    /**
     * 解析实体描述
     */
    @Schema(description = "解析实体描述")
    private String parseEntityDescription;

    /**
     * 状态
     */
    @Schema(description = "状态")
    @NotNull(message = "状态不能为空")
    private Boolean status;

}