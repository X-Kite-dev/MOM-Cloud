package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;


/**
 * <p>
 * 质检任务生成记录 实体类
 * </p>
 *
 * @author kitex
 * @since 2024-02-06
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TenantTable
@Accessors(chain = true)
@Schema(description = "质检任务生成记录")
@TableName("mes_inspection_gen_record")
public class MesInspectionGenRecordEntity extends BaseEntity<MesInspectionGenRecordEntity> {

    /**
     * 生产工单号
     */
    @Schema(description = "生产工单号")
    private String taskCode;

    /**
     * 工序任务号
     */
    @Schema(description = "工序任务号")
    private String produceTaskCode;

    /**
     * 规则编码
     */
    @Schema(description = "规则编码")
    private String ruleCode;

    /**
     * 规则名称
     */
    @Schema(description = "规则名称")
    private String ruleName;

    /**
     * 质检任务编码
     */
    @Schema(description = "质检任务编码")
    private Long inspectionId;

    /**
     * 生成质检类型 首中末
     */
    @Schema(description = "生成质检类型 首中末")
    private String inspectionType;

    /**
     * 生成时间
     */
    @Schema(description = "生成时间")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime genTime;

    /**
     * 生成班次
     */
    @Schema(description = "生成班次")
    private String genShiftCode;

    /**
     * 下次生成时间
     */
    @Schema(description = "下次生成时间")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime nextTime;

    /**
     * 下次生成班次
     */
    @Schema(description = "下次生成班次")
    private String nextShift;

    /**
     * 是否生成成功
     */
    @Schema(description = "是否生成成功")
    private String genFlag;

    /**
     * 生成说明
     */
    @Schema(description = "生成说明")
    private String genRemark;

    /**
     * 质检结果
     */
    @Schema(description = "质检结果")
    private String inspectionResult;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

} 