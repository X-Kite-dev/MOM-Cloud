package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.util.TenantTable;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/** 追加留存的修订依据；审核通过不等于MES已执行。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName(value = "qms_inspection_revision_review", schema = "mom_qms")
public class QmsInspectionRevisionReviewEntity {
    @TableId
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    private String factoryCode;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;

    private String requestFingerprint;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long reviewedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Date reviewedAt;

    private String decision;
    private String note;
}
