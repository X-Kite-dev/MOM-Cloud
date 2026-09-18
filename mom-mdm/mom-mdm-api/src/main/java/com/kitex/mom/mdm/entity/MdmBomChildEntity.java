package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * BOM
 *
 * @author kitex
 * @date 2024-10-31 09:14:59
 */
@Data
@TenantTable
@TableName("mdm_bom_child")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "mdm_bom_child")
public class MdmBomChildEntity extends BaseEntity<MdmBomChildEntity> {

    /**
     * BOM ID
     */
    @Schema(description = "BOM ID")
    private Long bomId;

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String materialCode;

    @Schema(description = "替代料编码集合，兼容旧业务展示，由结构化策略自动生成")
    private String replaceCodes;

    @Schema(description = "替代料结构化策略 JSON")
    private String substitutePolicy;

    @Schema(description = "是否允许主料与多个替代料混合消耗")
    private Boolean substituteMixAllowed;

    @Schema(description = "选用顺序：MAIN_FIRST、SUBSTITUTE_FIRST")
    private String substituteStrategy;

    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String materialName;

    /**
     * 物料规格型号
     */
    @Schema(description = "物料规格型号")
    private String materialSpec;

    /**
     * 单位
     */
    @Schema(description = "单位")
    private String unit;

    /**
     * 数量
     */
    @Schema(description = "数量")
    private BigDecimal quantity;

    /**
     * 子项BOM ID
     */
    @Schema(description = "子项BOM ID")
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long subitemBomId;

    /**
     * 物料属性
     */
    @Schema(description = "物料属性")
    private String materialAttribute;

    /**
     * 固定损耗率
     */
    @Schema(description = "固定损耗率")
    private BigDecimal fixedLossRate;

    /**
     * 变动损耗率
     */
    @Schema(description = "变动损耗率")
    private BigDecimal variableLossRate;

    /**
     * 默认发料仓库
     */
    @Schema(description = "默认发料仓库")
    private String defaultIssuanceWarehouse;

    /**
     * 是否发损耗 (1=是, 0=否)
     */
    @Schema(description = "是否发损耗 (1=是, 0=否)")
    private Boolean isLossIssued;

    /**
     * 用量类型
     */
    @Schema(description = "用量类型")
    private String usageType;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}
