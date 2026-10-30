package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

@Data
@Accessors(chain = true)
@TableName(value = "mom_qms.qms_inspection_revision_run_receipt", autoResultMap = true)
public class QmsInspectionRevisionRunReceiptEntity {
    @TableId private Long id;
    private Long requestId;
    private Long tenantId;
    private String factoryCode;
    private Long taskId;
    private String operation;
    private String payload;
    private String fingerprint;
    private Date receivedAt;
}
