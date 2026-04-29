package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 客户零件号
 *
 * @author kitex
 * @date 2024-10-30 14:03:10
 */
@Data
@TenantTable
@TableName("mdm_customer_part")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "客户零件号")
public class MdmCustomerPartEntity extends BaseEntity<MdmCustomerPartEntity> {

    /**
     * 客户编码
     */
    @Schema(description = "客户编码")
    private String customerCode;

    /**
     * 客户名称
     */
    @Schema(description = "客户名称")
    private String customerName;

    /**
     * 零件号
     */
    @Schema(description = "零件号")
    private String partNumber;

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
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String materialSpec;

    /**
     * 状态
     */
    @Schema(description = "状态")
    private Boolean status;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    @Schema(description = "一物一码正则")
    private String regular;

}