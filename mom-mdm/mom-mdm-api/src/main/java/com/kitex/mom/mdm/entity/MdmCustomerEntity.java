package com.kitex.mom.mdm.entity;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.github.yulichang.annotation.EntityMapping;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 客户表
 *
 * @author kitex
 * @date 2024-10-27 16:22:18
 */
@Data
@TenantTable
@TableName("mdm_customer")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "客户表")
public class MdmCustomerEntity extends BaseEntity<MdmCustomerEntity> {

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
     * 电话
     */
    @Schema(description = "电话")
    private String phone;

    /**
     * 联系人
     */
    @Schema(description = "联系人")
    private String contactPerson;

    /**
     * 本地仓库
     */
    @Schema(description = "本地仓库")
    private String localWarehouse;

    /**
     * 三方仓库
     */
    @Schema(description = "三方仓库")
    private String thirdPartyWarehouse;

    /**
     * 信用等级
     */
    @Schema(description = "信用等级")
    private String creditRating;

    /**
     * 客户来源
     */
    @Schema(description = "客户来源")
    private String customerSource;

    /**
     * 状态 (true=启用, false=禁用)
     */
    @Schema(description = "状态 (true=启用, false=禁用)")
    private Boolean status;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remarks;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    @ExcelIgnore
    @TableField(exist = false)
    @EntityMapping(thisField = "id", joinField = "customerId")
    private List<MdmCustomerDeliverEntity> mdmCustomerDeliverList;

}