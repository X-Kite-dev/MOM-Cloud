package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class MdmBomChildDTO {

    private Long id;

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

    @Schema(description = "替代料编码集合（兼容字段）")
    private String replaceCodes;

    @Schema(description = "替代料结构化策略 JSON")
    private String substitutePolicy;

    @Schema(description = "是否允许主料与替代料混用")
    private Boolean substituteMixAllowed;

    @Schema(description = "选用顺序：MAIN_FIRST、SUBSTITUTE_FIRST")
    private String substituteStrategy;

    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String materialName;

    /** 工单冻结时的子件规格，历史快照缺少规格时可空。 */
    @Schema(description = "冻结子件规格")
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
    private String factoryCode;


    private BigDecimal noticeNum;

    @Schema(description = "已投数量")
    private BigDecimal feedNum;

    @Schema(description = "总需求量")
    private BigDecimal needNum;

    @Schema(description = "总消耗量")
    private BigDecimal usedNum;
}
