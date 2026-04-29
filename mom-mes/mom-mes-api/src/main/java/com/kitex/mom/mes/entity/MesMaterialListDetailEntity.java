package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * <p>
 * 用料清单明细表 实体类
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TenantTable
@Accessors(chain = true)
@Schema(description = "用料清单明细表")
@TableName("mes_material_list_detail")
public class MesMaterialListDetailEntity extends BaseEntity<MesMaterialListDetailEntity> {

    /**
     * 用料清单主表ID
     */
    @Schema(description = "用料清单主表ID")
    private Long materialListId;

    /**
     * 生产工单ID
     */
    @Schema(description = "生产工单ID")
    private Long taskId;

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String materialCode;

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
     * BOM单位用量
     */
    @Schema(description = "BOM单位用量")
    private BigDecimal bomQuantity;

    /**
     * 需求数量（根据生产数量计算）
     */
    @Schema(description = "需求数量（根据生产数量计算）")
    private BigDecimal requiredQuantity;

    /**
     * 已领料数量
     */
    @Schema(description = "已领料数量")
    private BigDecimal pickedQuantity;

    /**
     * 已退料数量
     */
    @Schema(description = "已退料数量")
    private BigDecimal returnedQuantity;

    /**
     * 实际消耗数量（领料-退料）
     */
    @Schema(description = "实际消耗数量（领料-退料）")
    private BigDecimal actualQuantity;

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
    private String defaultWarehouse;

    /**
     * 是否发损耗（1=是，0=否）
     */
    @Schema(description = "是否发损耗（1=是，0=否）")
    private Boolean isLossIssued;

    /**
     * 用量类型
     */
    @Schema(description = "用量类型")
    private String usageType;

    /**
     * 领料状态：PENDING-待领料，PICKING-领料中，PICKED-已领料，COMPLETED-完成
     */
    @Schema(description = "领料状态：PENDING-待领料，PICKING-领料中，PICKED-已领料，COMPLETED-完成")
    private String pickStatus;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}