package com.kitex.mom.common.seq.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 编码规则VO
 *
 * @author claude
 * @date 2025-08-11
 */
@Data
@Schema(description = "编码规则VO")
public class CommonCodeStrategyVO {

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

    /**
     * 创建时间
     */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    /**
     * 创建人
     */
    @Schema(description = "创建人")
    private String createBy;

    /**
     * 更新人
     */
    @Schema(description = "更新人")
    private String updateBy;

}