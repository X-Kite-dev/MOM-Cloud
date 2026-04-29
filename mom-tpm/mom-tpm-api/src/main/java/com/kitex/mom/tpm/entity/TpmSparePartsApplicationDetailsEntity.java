package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 备件申请明细表（记录具体物料及出入库位置信息）
 *
 * @author kitex
 * @date 2025-03-18 10:35:14
 */
@Data
@TableName("tpm_spare_parts_application_details")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "备件申请明细表（记录具体物料及出入库位置信息）")
public class TpmSparePartsApplicationDetailsEntity extends Model<TpmSparePartsApplicationDetailsEntity> {


    /**
     * 主键ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private Long id;

    /**
     * 申请号
     */
    @Schema(description = "申请号")
    private String applyCode;

    /**
     * 备件编码
     */
    @Schema(description = "备件编码")
    private String sparePartCode;

    /**
     * 备件名称
     */
    @Schema(description = "备件名称")
    private String sparePartName;

    /**
     * 出库库房
     */
    @Schema(description = "出库库房")
    private String storeroomCode;

    /**
     * 出库库位
     */
    @Schema(description = "出库库位")
    private String locationCode;

    /**
     * 备件规格（型号/参数）
     */
    @Schema(description = "备件规格（型号/参数）")
    private String specification;

    /**
     * 申请数量（必须为正整数）
     */
    @Schema(description = "申请数量（必须为正整数）")
    private BigDecimal quantityRequested;

    /**
     * 备件单位
     */
    @Schema(description = "备件单位")
    private String unit;

    @TableField(exist = false)
    @Schema(description = "审批状态（0待审/1通过/2驳回）")
    private String approvalStatus;
}