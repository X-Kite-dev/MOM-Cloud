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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <p>
 * 报废品管理 实体类
 * </p>
 *
 * @author system
 * @since 2025-01-27
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Accessors(chain = true)
@Schema(description = "报废品管理")
@TableName("mes_scrap_material")
@TenantTable
public class MesScrapMaterialEntity extends BaseEntity<MesScrapMaterialEntity> {

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
     * 数量
     */
    @Schema(description = "数量")
    private BigDecimal quantity;

    /**
     * 操作人员
     */
    @Schema(description = "操作人员")
    private String operator;

    /**
     * 工单号
     */
    @Schema(description = "工单号")
    private String workOrder;

    /**
     * 时间
     */
    @Schema(description = "时间")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime scrapTime;

    /**
     * 班次
     */
    @Schema(description = "班次")
    private String shift;

    /**
     * 物料类型（原料报废，制程报废）
     */
    @Schema(description = "物料类型（原料报废，制程报废）")
    private String materialType;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 单位
     */
    @Schema(description = "单位")
    private String unit;

    /**
     * 报废原因
     */
    @Schema(description = "报废原因")
    private String scrapReason;

    /**
     * 报废位置
     */
    @Schema(description = "报废位置")
    private String scrapLocation;

    /**
     * 处理状态
     */
    @Schema(description = "处理状态")
    private String status;

    /**
     * 审核人
     */
    @Schema(description = "审核人")
    private String auditor;

    /**
     * 审核时间
     */
    @Schema(description = "审核时间")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime auditTime;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;
} 