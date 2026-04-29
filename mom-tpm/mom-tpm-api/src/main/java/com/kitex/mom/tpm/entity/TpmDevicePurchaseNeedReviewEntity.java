package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 设备采购需求审批表
 *
 * @author kitex
 * @date 2025-03-26 10:10:02
 */
@Data
@TenantTable
@TableName("tpm_device_purchase_need_review")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "设备采购需求审批表")
public class TpmDevicePurchaseNeedReviewEntity extends BaseEntity<TpmDevicePurchaseNeedReviewEntity> {


    /**
     * 主键
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键")
    private Long id;

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
     * 附件JSON串
     */
    @Schema(description = "附件JSON串")
    private String fileJson;

    /**
     * 申请人编码
     */
    @Schema(description = "申请人编码")
    private String applicationCode;

    /**
     * 申请人
     */
    @Schema(description = "申请人")
    private String applicationName;

    /**
     * 申请时间
     */
    @Schema(description = "申请时间")
    private LocalDate applicationTime;

    /**
     * 审核人编码
     */
    @Schema(description = "审核人编码")
    private String reviewCode;

    /**
     * 审核人
     */
    @Schema(description = "审核人")
    private String reviewName;

    /**
     * 审核时间
     */
    @Schema(description = "审核时间")
    private LocalDateTime reviewTime;

    /**
     * 驳回原因
     */
    @Schema(description = "驳回原因")
    private String rejectReason;

    /**
     * 申请状态（待审核、审核通过、审核驳回，已生成）
     */
    @Schema(description = "申请状态（待审核、审核通过、审核驳回，已生成）")
    private String state;

    /**
     * 需求说明
     */
    @Schema(description = "需求说明")
    private String remark;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}