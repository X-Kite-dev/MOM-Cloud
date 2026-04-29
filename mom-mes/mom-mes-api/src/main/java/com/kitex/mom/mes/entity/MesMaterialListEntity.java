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
 * 用料清单主表 实体类
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TenantTable
@Accessors(chain = true)
@Schema(description = "用料清单主表")
@TableName("mes_material_list")
public class MesMaterialListEntity extends BaseEntity<MesMaterialListEntity> {

    /**
     * 生产工单ID
     */
    @Schema(description = "生产工单ID")
    private Long taskId;

    /**
     * 工单编码
     */
    @Schema(description = "工单编码")
    private String taskCode;

    /**
     * 产品编码
     */
    @Schema(description = "产品编码")
    private String productCode;

    /**
     * 产品名称
     */
    @Schema(description = "产品名称")
    private String productName;

    /**
     * 产品规格型号
     */
    @Schema(description = "产品规格型号")
    private String productSpec;

    /**
     * BOM ID
     */
    @Schema(description = "BOM ID")
    private Long bomId;

    /**
     * BOM版本
     */
    @Schema(description = "BOM版本")
    private String bomVersion;

    /**
     * BOM快照
     */
    @Schema(description = "BOM快照")
    private String bomSnapshot;

    /**
     * 计划生产数量
     */
    @Schema(description = "计划生产数量")
    private BigDecimal planQuantity;

    /**
     * 状态：NEW-新建，PICKING-领料中，COMPLETED-完成
     */
    @Schema(description = "状态：NEW-新建，PICKING-领料中，COMPLETED-完成")
    private String status;

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