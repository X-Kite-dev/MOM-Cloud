package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.util.TenantTable;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/** 普通MES工序检验事件发件进度；id引用不可变journal原件，RECEIVED不代表业务应用。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName(value = "qms_inspection_event_outbox", schema = "mom_qms")
public class QmsInspectionEventOutboxEntity {
    @TableId
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    private String factoryCode;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;

    private Long sequenceNo;
    private String fingerprint;
    private String status;
    private Integer attemptCount;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Date nextAttemptTime;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Date createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Date confirmedAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Date mesReceivedAt;

    private String lastError;
}
