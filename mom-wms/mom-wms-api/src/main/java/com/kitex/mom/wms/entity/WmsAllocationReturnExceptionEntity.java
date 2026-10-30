package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TenantTable
@TableName("wms_allocation_return_exception")
@EqualsAndHashCode(callSuper = true)
public class WmsAllocationReturnExceptionEntity extends BaseEntity<WmsAllocationReturnExceptionEntity> {
    private String caseNo;
    private Long allocationBillId;
    private String reportRequestId;
    private String reportSnapshot;
    private String reasonCode;
    private String reasonDescription;
    private String evidence;
    private String status;
    private String reporterCode;
    private String reporterName;
    private LocalDateTime reportedTime;
    private String reviewRequestId;
    private String reviewSnapshot;
    private String decision;
    private String reviewComment;
    private String dispositionCode;
    private String referenceNo;
    private String reviewerCode;
    private String reviewerName;
    private LocalDateTime reviewedTime;

    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
