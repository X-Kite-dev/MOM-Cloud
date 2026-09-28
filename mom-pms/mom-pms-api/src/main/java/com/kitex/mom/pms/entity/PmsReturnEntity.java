package com.kitex.mom.pms.entity;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.github.yulichang.annotation.EntityMapping;
import com.kitex.common.core.entity.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.time.LocalDateTime;

/**
 * 采购退货
 *
 * @author kitex
 * @date 2025-02-24 08:38:40
 */
@Data
@TableName("pms_return")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "采购退货")
public class PmsReturnEntity extends BaseEntity<PmsReturnEntity> {

    /**
     * 采购退货号
     */
    @Schema(description = "采购退货号")
    private String returnNo;

    /**
     * 采购订单号
     */
    @Schema(description = "采购订单号")
    private String orderNo;

    /**
     * 供应商编码
     */
    @Schema(description = "供应商编码")
    private String supplierCode;

    /**
     * 供应商名称
     */
    @Schema(description = "供应商名称")
    private String supplierName;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 是否通知仓库
     */
    @Schema(description = "是否通知仓库")
    private Boolean warehouse;

    /**
     * 是否已通知
     */
    @Schema(description = "是否已通知")
    private Boolean warehouseStatus;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /** 草稿、待复核、待仓库出库、部分出库、已完成、已驳回、已取消、已失效。 */
    @Schema(description = "受控退供状态")
    private String workflowStatus;

    @Schema(description = "申请人账号")
    private String applicantCode;

    @Schema(description = "申请人姓名")
    private String applicantName;

    @Schema(description = "申请时间")
    private LocalDateTime appliedTime;

    @Schema(description = "复核人账号")
    private String reviewerCode;

    @Schema(description = "复核人姓名")
    private String reviewerName;

    @Schema(description = "复核时间")
    private LocalDateTime reviewedTime;

    @Schema(description = "复核意见")
    private String reviewRemark;

    @Schema(description = "提交永久请求号")
    private String submitRequestId;

    @Schema(description = "复核永久请求号")
    private String reviewRequestId;

    @Schema(description = "冻结事实指纹")
    private String sourceFingerprint;

    @Schema(description = "WMS出库通知ID")
    private Long wmsNoticeId;

    @Schema(description = "WMS通知状态")
    private String wmsNoticeStatus;

    @Schema(description = "WMS实际退供完成时间")
    private LocalDateTime completedTime;

    @ExcelIgnore
    @TableField(exist = false)
    @EntityMapping(thisField = "id", joinField = "returnId")
    private List<PmsReturnDetailEntity> pmsReturnDetailList;
}
