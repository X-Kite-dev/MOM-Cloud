package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.util.TenantTable;
import com.kitex.mom.mdm.base.BusinessEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 计时计件表
 *
 * @author kitex
 * @date 2025-09-03 17:23:53
 */
@Data
@TenantTable
@TableName("mdm_time_piece")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "计时计件表")
public class MdmTimePieceEntity extends BusinessEntity<MdmTimePieceEntity> {

    /**
     * 产品编码
     */
    @Schema(description = "产品编码")
    private String matCode;

    /**
     * 产品名称
     */
    @Schema(description = "产品名称")
    private String matName;

    /**
     * 产品规格型号
     */
    @Schema(description = "产品规格型号")
    private String matSpec;

    /**
     * 工序编码
     */
    @Schema(description = "工序编码")
    private String procedureCode;

    /**
     * 工序名称
     */
    @Schema(description = "工序名称")
    private String procedureName;

    /**
     * 类型 0 计时 1 计件
     */
    @Schema(description = "类型 0 计时 1 计件")
    private String type;

    /**
     * 单价
     */
    @Schema(description = "单价")
    private BigDecimal price;

    /**
     * 启用状态
     */
    @Schema(description = "启用状态")
    private Boolean status;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;
}