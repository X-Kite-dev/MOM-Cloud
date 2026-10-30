package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.util.TenantTable;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * 已收检验事件的不可变原文和收件依据；不继承可更新/逻辑删除的业务基类。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName(value = "mes_inspection_event_inbox", schema = "mom_mes")
@Schema(description = "普通工序检验永久收件，未应用，不代表放行")
public class MesInspectionEventInboxEntity {
    @TableId
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    private String factoryCode;
    private String inspectionType;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long historyId;

    private String eventType;
    private Long sequenceNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long previousEventId;

    private String previousFingerprint;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Date occurredAt;

    private String payload;
    private String fingerprint;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long genRecordId;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Date receivedAt;
}
