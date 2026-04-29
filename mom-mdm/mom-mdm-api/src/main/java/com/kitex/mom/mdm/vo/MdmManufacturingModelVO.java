package com.kitex.mom.mdm.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 制造模型VO
 *
 * @author claude
 * @date 2025-08-11
 */
@Data
@Schema(description = "制造模型VO")
public class MdmManufacturingModelVO {

    /**
     * 主键ID
     */
    @Schema(description = "主键ID")
    private Long id;

    /**
     * 车间
     */
    @Schema(description = "车间")
    private String workshop;

    /**
     * 报工方式
     */
    @Schema(description = "报工方式")
    private String reportingMethod;

    /**
     * 是否班组任务单
     */
    @Schema(description = "是否班组任务单")
    private Boolean teamTask;

    /**
     * 是否夸线体工位报工
     */
    @Schema(description = "是否夸线体工位报工")
    private Boolean crossLineWorkstation;

    /**
     * 不良处理方式
     */
    @Schema(description = "不良处理方式")
    private String defectProcessingMethod;

    /**
     * 返修模式
     */
    @Schema(description = "返修模式")
    private String returnRepairMode;

    /**
     * 是否开工验证
     */
    @Schema(description = "是否开工验证")
    private Boolean startValidation;

    /**
     * 是否上料验证
     */
    @Schema(description = "是否上料验证")
    private Boolean materialValidation;

    /**
     * 是否先领先用
     */
    @Schema(description = "是否先领先用")
    private Boolean leadUse;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    private String factoryCode;

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