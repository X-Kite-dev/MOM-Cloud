package com.kitex.mom.common.seq.entity;

import com.kitex.common.core.entity.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 编码规则
 * 规则编码，规则描述，规则类型（编码，标签码），标签码类型（包装箱码，客户码，内包装码，样本码，SN），长度，是否校验长度，是否默认，段数，解析实体类，解析实体描述，状态
 */
@Data
public class CommonCodeStrategy extends BaseEntity<CommonCodeStrategy> {

    /**
     * 父级编码
     */
    @Schema(description = "父级编码")
    private String parentCode;

    /**
     * 规则编码
     */
    @Schema(description = "规则编码")
    private String code;

    /**
     * 描述
     */
    @Schema(description = "描述")
    private String description;

    /**
     * 规则类型（编码，标签码）
     */
    @Schema(description = "规则类型（编码，标签码）")
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
    private Integer length;

    /**
     * 是否校验长度
     */
    @Schema(description = "是否校验长度")
    private Boolean checkLength;

    /**
     * 是否默认
     */
    @Schema(description = "是否默认")
    private Boolean isDefault;

    /**
     * 段数
     */
    @Schema(description = "段数")
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
    private Boolean status;

}
