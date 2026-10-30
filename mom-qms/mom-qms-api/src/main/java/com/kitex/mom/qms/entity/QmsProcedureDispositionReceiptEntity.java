package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/** 工序处置的永久原件，进度沿普通事件发件/收件账管理。 */
@Data
@Accessors(chain = true)
@TableName("mom_qms.qms_procedure_disposition_receipt")
public class QmsProcedureDispositionReceiptEntity {
    private Long id;
    private Long tenantId;
    private String factoryCode;
    private Long taskId;
    private String requestId;
    private Long handleRecordId;
    private Long historyId;
    private Long eventId;
    private String payload;
    private String fingerprint;
    private Date createdAt;
}
