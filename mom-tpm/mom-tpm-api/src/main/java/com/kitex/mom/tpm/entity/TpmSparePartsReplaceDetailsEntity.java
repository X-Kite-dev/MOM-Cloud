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
 * 备件更换单明细表
 *
 * @author kitex
 * @date 2025-03-21 16:16:37
 */
@Data
@TableName("tpm_spare_parts_replace_details")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "备件更换单明细表")
public class TpmSparePartsReplaceDetailsEntity extends Model<TpmSparePartsReplaceDetailsEntity> {


    /**
     * 主键ID，自增唯一标识
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID，自增唯一标识")
    private Long id;

    /**
     * 单据唯一编码
     */
    @Schema(description = "单据唯一编码")
    private String docSn;

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
     * 备件型号
     */
    @Schema(description = "备件型号")
    private String specification;

    /**
     * 计量单位
     */
    @Schema(description = "计量单位")
    private String unit;

    @TableField(exist = false)
    @Schema(description = "申请数量")
    private BigDecimal quantityRequested;

    @TableField(exist = false)
    @Schema(description = "使用数量")
    private BigDecimal useNum;
}