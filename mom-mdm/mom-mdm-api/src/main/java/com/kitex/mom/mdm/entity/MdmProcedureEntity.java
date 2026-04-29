package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 工序表 - 用于管理生产工艺过程中的各个工序信息
 * 包含工序的基本信息、加工参数、时间参数等生产相关数据
 *
 * @author kitex
 * @date 2024-10-31 17:16:43
 */
@Data
@TenantTable
@TableName("mdm_procedure")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "工序表")
public class MdmProcedureEntity extends BaseEntity<MdmProcedureEntity> {

    /**
     * 工序编码 - 工序的唯一标识符
     */
    @Schema(description = "工序编码")
    private String processCode;

    /**
     * 工序名称 - 工序的中文名称，用于显示和识别
     */
    @Schema(description = "工序名称")
    private String processName;

    /**
     * 状态 - 工序的启用状态（true-启用，false-禁用）
     */
    @Schema(description = "状态")
    private Boolean status;

    /**
     * 作业名称 - 工序的具体作业名称
     */
    @Schema(description = "作业名称")
    private String operationName;

    /**
     * 作业说明 - 对工序作业的详细描述和操作说明
     */
    @Schema(description = "作业说明")
    private String operationDescription;

    /**
     * 加工组织 - 负责该工序加工的组织ID
     */
    @Schema(description = "加工组织")
    private Long processingOrganization;

    /**
     * 加工类型 - 工序的加工方式分类（如：内部加工、外部加工等）
     */
    @Schema(description = "加工类型")
    private String processingType;

    /**
     * 检验方式 - 工序完成后的质量检验方式（如：全检、抽检等）
     */
    @Schema(description = "检验方式")
    private String inspectionMethod;

    /**
     * 汇报方式 - 工序完成后的生产数据汇报方式
     */
    @Schema(description = "汇报方式")
    private String reportingMethod;

    /**
     * 生产转换数量 - 生产过程中的数量转换系数
     */
    @Schema(description = "生产转换数量")
    private BigDecimal productionConversionQuantity;

    /**
     * 生产计量单位 - 生产数量的计量单位编码
     */
    @Schema(description = "生产计量单位")
    private String productionMeasureUnit;

    /**
     * 工序转换数量 - 工序内部的转换系数
     */
    @Schema(description = "工序转换数量")
    private BigDecimal processConversionQuantity;

    /**
     * 工序计量单位 - 工序数量的计量单位编码
     */
    @Schema(description = "工序计量单位")
    private String processMeasureUnit;

    /**
     * 活动基本批量 - 工序活动的基本批量数量
     */
    @Schema(description = "活动基本批量")
    private BigDecimal activityBasicBatch;

    /**
     * 批量单位 - 基本批量的计量单位编码
     */
    @Schema(description = "批量单位")
    private String batchUnit;

    /**
     * 准备时间 - 工序开始前的准备时间
     */
    @Schema(description = "准备时间")
    private BigDecimal setupTime;

    /**
     * 准备时间单位 - 准备时间的计量单位
     */
    @Schema(description = "准备时间单位")
    private String setupTimeUnit;

    /**
     * 人工加工时间 - 单件产品的人工加工时间
     */
    @Schema(description = "人工加工时间")
    private BigDecimal manualProcessingTime;

    /**
     * 人工加工时间单位 - 人工加工时间的计量单位
     */
    @Schema(description = "人工加工时间单位")
    private String manualProcessingTimeUnit;

    /**
     * 机器加工时间 - 单件产品的机器加工时间
     */
    @Schema(description = "机器加工时间")
    private BigDecimal machineProcessingTime;

    /**
     * 机器加工时间单位 - 机器加工时间的计量单位
     */
    @Schema(description = "机器加工时间单位")
    private String machineProcessingTimeUnit;

    /**
     * 拆卸时间 - 工序结束后的设备拆卸时间
     */
    @Schema(description = "拆卸时间")
    private BigDecimal disassemblyTime;

    /**
     * 拆卸时间单位 - 拆卸时间的计量单位
     */
    @Schema(description = "拆卸时间单位")
    private String disassemblyTimeUnit;

    /**
     * 提前期（小时） - 工序开始前的准备提前期
     */
    @Schema(description = "提前期（小时）")
    private BigDecimal leadTimeHours;

    /**
     * 偏置时间（小时） - 工序排程的偏置时间
     */
    @Schema(description = "偏置时间（小时）")
    private BigDecimal offsetTimeHours;

    /**
     * 委外提前期（天） - 外协加工的提前天数
     */
    @Schema(description = "委外提前期（天）")
    private Integer outsourcingLeadTimeDays;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}