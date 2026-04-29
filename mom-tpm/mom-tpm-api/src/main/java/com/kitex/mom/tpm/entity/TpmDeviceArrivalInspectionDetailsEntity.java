package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 设备到货质检明细表
 *
 * @author kitex
 * @date 2025-03-26 10:29:01
 */
@Data
@TableName("tpm_device_arrival_inspection_details")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "设备到货质检明细表")
public class TpmDeviceArrivalInspectionDetailsEntity extends Model<TpmDeviceArrivalInspectionDetailsEntity> {


    /**
     * 主键
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键")
    private Long id;

    /**
     * 质检单据编码
     */
    @Schema(description = "质检单据编码")
    private String qcCode;

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
     * 设备编码
     */
    @Schema(description = "设备编码")
    private String deviceCode;

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
}