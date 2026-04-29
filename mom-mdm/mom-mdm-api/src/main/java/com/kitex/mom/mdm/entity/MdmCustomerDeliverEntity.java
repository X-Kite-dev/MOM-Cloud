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
 * 客户表
 *
 * @author kitex
 * @date 2025-02-18 09:00:39
 */
@Data
@TenantTable
@TableName("mdm_customer_deliver")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "mdm_customer_deliver")
public class MdmCustomerDeliverEntity extends BaseEntity<MdmCustomerDeliverEntity> {

    /**
     * 客户id
     */
    @Schema(description = "客户id")
    private Long customerId;
    /**
     * 仓库
     */
    @Schema(description = "仓库")
    private String warehouse;
    /**
     * 发货地址
     */
    @Schema(description = "发货地址")
    private String address;
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