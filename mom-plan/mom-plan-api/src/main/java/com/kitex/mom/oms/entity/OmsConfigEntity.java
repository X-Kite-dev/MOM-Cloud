package com.kitex.mom.oms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 订单配置表
 *
 * @author kitex
 * @date 2025-03-24 10:24:55
 */
@Data
@TenantTable
@TableName("oms_config")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "订单配置表")
public class OmsConfigEntity extends BaseEntity<OmsConfigEntity> {

    /**
     * 冻结天数配置
     */
    @Schema(description = "冻结天数配置")
    private Integer frozenDay;

    /**
     * 下发天数配置
     */
    @Schema(description = "下发天数配置")
    private Integer issueDay;

    /**
     * 是否自动创建发货和排产计划
     */
    @Schema(description = "是否自动创建发货和排产计划")
    private Boolean issue;

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