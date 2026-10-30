package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

@Data
@Accessors(chain = true)
@TableName(value = "mom_qms.qms_inspection_revision_outcome", autoResultMap = true)
public class QmsInspectionRevisionOutcomeEntity {
    @TableId private Long id;
    private Long tenantId;
    private String factoryCode;
    private Long taskId;
    private String decision;
    private String payload;
    private String fingerprint;
    private Date createdAt;
}
