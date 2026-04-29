package com.kitex.mom.pms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 供应商-供货物料表
 *
 * @author kitex
 * @date 2025-03-19 18:08:41
 */
@Data
@TenantTable
@TableName("pms_supplier_mat")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "供应商-供货物料表")
public class PmsSupplierMatEntity extends BaseEntity<PmsSupplierMatEntity> {

    /**
     * 供应商主键id
     */
    @Schema(description = "供应商主键id")
    private Long supplierId;

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
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 单位
     */
    @Schema(description = "单位")
    private String matUnit;

    /**
     * 是否免检 (false=否, true=是)
     */
    @Schema(description = "是否免检 (false=否, true=是)")
    private Boolean isQuality;

    /**
     * 供货能力评级（A、B、C、D）
     */
    @Schema(description = "供货能力评级（A、B、C、D）")
    private String rating;

    /**
     * 供货能力评分
     */
    @Schema(description = "供货能力评分")
    private BigDecimal score;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;
}