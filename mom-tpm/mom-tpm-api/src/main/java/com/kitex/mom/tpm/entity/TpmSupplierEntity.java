package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.kitex.common.core.entity.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 设备供应商主表
 *
 * @author kitex
 * @date 2025-03-25 11:57:15
 */
@Data
@TableName("tpm_supplier")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "设备供应商主表")
public class TpmSupplierEntity extends BaseEntity<TpmSupplierEntity> {


    /**
     * 主键ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private Long id;

    /**
     * 供应商编码
     */
    @Schema(description = "供应商编码")
    private String code;

    /**
     * 供应商名称
     */
    @Schema(description = "供应商名称")
    private String name;

    /**
     * 类型编码
     */
    @Schema(description = "类型编码")
    private String classCode;

    /**
     * 类型名称
     */
    @Schema(description = "类型名称")
    private String className;

    /**
     * 联系人
     */
    @Schema(description = "联系人")
    private String contact;


    /**
     * 联系人职位
     */
    @Schema(description = "联系人职位")
    private String contactPosition;

    /**
     * 联系人电话
     */
    @Schema(description = "联系人电话")
    private String contactPhone;

    /**
     * 联系人邮箱
     */
    @Schema(description = "联系人邮箱")
    private String contactEmail;

    /**
     * 联系地址
     */
    @Schema(description = "联系地址")
    private String contactAddress;

    /**
     * 备注说明
     */
    @Schema(description = "备注说明")
    private String remark;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}