package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@TenantTable
@TableName("wms_inventory_adjustment_approval")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "盘点差异审批及调账依据")
public class WmsInventoryAdjustmentApprovalEntity extends BaseEntity<WmsInventoryAdjustmentApprovalEntity> {
    private Long planId;
    private Long planDetailId;
    private String warehouseCode;
    private String requestId;
    private String status;
    private Double expectedCurrentNumber;
    private Double expectedInventoryNum;
    private String selectedDetailJson;
    private String reason;
    private String applicantCode;
    private LocalDateTime submittedTime;
    private String reviewerCode;
    private LocalDateTime reviewedTime;
    private String reviewComment;
    private String revokedBy;
    private LocalDateTime revokedTime;
    private String revokeReason;
    private LocalDateTime postedTime;

    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
