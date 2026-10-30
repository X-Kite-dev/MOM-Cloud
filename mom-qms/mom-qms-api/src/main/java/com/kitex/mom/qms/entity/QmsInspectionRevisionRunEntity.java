package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

@Data
@Accessors(chain = true)
@TableName(value = "mom_qms.qms_inspection_revision_run", autoResultMap = true)
public class QmsInspectionRevisionRunEntity {
    @TableId private Long id;
    private Long tenantId;
    private String factoryCode;
    private Long taskId;
    private Long createdBy;
    private Date createdAt;
    private String phase;
    private String status;
    private Integer attemptCount;
    private Date nextAttemptAt;
    private Date updatedAt;
    private String lastError;
    private Long version;
}
