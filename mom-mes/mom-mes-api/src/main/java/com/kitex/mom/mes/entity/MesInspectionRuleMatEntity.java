package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>
 * 质检任务生成规则-物料关联 实体类
 * </p>
 *
 * @author kitex
 * @since 2024-02-06
 */
@Data
@Accessors(chain = true)
@Schema(description = "质检任务生成规则-物料关联")
@TableName("mes_inspection_rule_mat")
public class MesInspectionRuleMatEntity {

    /**
     * 主键id
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键id")
    private Long id;

    /**
     * 规则编码
     */
    @Schema(description = "规则编码")
    private String ruleCode;

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String matCode;

    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String matName;

    /**
     * 物料规格
     */
    @Schema(description = "物料规格")
    private String matSpec;
} 