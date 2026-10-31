package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.util.TenantTable;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/** Immutable received business command; deliberately has no update/delete base entity. */
@Data
@Accessors(chain = true)
@TenantTable
@TableName(value = "qms_rework_completion_receipt", schema = "mom_qms")
public class QmsReworkCompletionReceiptEntity {
    @TableId private Long id;
    private Long tenantId;
    private String factoryCode;
    private String inspectionType;
    private Long orderId;
    private Long childTaskId;
    private String requestId;
    private String payload;
    private String payloadFingerprint;
    private Long receivedBy;
    private Date receivedAt;
}
