package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 设备采购单明细表
 *
 * @author kitex
 * @date 2025-03-26 10:16:55
 */
@Data
@TableName("tpm_device_purchase_details")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "设备采购单明细表")
public class TpmDevicePurchaseDetailsEntity extends Model<TpmDevicePurchaseDetailsEntity> {


    /**
     * 主键
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键")
    private Long id;

    /**
     * 采购单号
     */
    @Schema(description = "采购单号")
    private String purchaseCode;

    /**
     * 采购需求编码
     */
    @Schema(description = "采购需求编码")
    private String purchaseNeedCode;

    /**
     * 采购需求名称
     */
    @Schema(description = "采购需求名称")
    private String purchaseNeedName;

    /**
     * 设备名称
     */
    @Schema(description = "设备名称")
    private String deviceName;

    /**
     * 设备规格
     */
    @Schema(description = "设备规格")
    private String deviceSpec;

    /**
     * 设备型号
     */
    @Schema(description = "设备型号")
    private String deviceModel;

    /**
     * 设备分类编码
     */
    @Schema(description = "设备分类编码")
    private String classCode;

    /**
     * 设备分类
     */
    @Schema(description = "设备分类")
    private String className;

    /**
     * 采购数量
     */
    @Schema(description = "采购数量")
    private Integer purchaseNum;

    /**
     * 含税金额
     */
    @Schema(description = "含税金额")
    private BigDecimal allMoney;

    /**
     * 不含税金额
     */
    @Schema(description = "不含税金额")
    private BigDecimal taxRateMoney;

    /**
     * 税率
     */
    @Schema(description = "税率")
    private BigDecimal taxRate;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}