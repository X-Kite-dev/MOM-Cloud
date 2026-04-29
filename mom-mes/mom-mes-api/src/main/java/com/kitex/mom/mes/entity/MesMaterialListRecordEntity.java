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
 * 用料清单领退料记录表 实体类
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TenantTable
@Accessors(chain = true)
@Schema(description = "用料清单领退料记录表")
@TableName("mes_material_list_record")
public class MesMaterialListRecordEntity extends BaseEntity<MesMaterialListRecordEntity> {

    /**
     * 用料清单明细ID
     */
    @Schema(description = "用料清单明细ID")
    private Long materialListDetailId;

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
     * 操作类型：PICK-领料，RETURN-退料
     */
    @Schema(description = "操作类型：PICK-领料，RETURN-退料")
    private String operationType;

    /**
     * 操作数量
     */
    @Schema(description = "操作数量")
    private BigDecimal quantity;

    /**
     * 仓库编码
     */
    @Schema(description = "仓库编码")
    private String warehouseCode;

    /**
     * 仓库名称
     */
    @Schema(description = "仓库名称")
    private String warehouseName;

    /**
     * 批次号
     */
    @Schema(description = "批次号")
    private String batchNo;

    /**
     * 操作人员
     */
    @Schema(description = "操作人员")
    private String operator;

    /**
     * 操作时间
     */
    @Schema(description = "操作时间")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime operationTime;

    /**
     * 关联单据号（出库单、入库单）
     */
    @Schema(description = "关联单据号（出库单、入库单）")
    private String referenceBillNo;

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