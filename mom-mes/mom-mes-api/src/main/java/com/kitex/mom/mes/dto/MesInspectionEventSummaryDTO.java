package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * 应用索引摘要，不映射或携带决策大字段。
 */
@Data
@Accessors(chain = true)
public class MesInspectionEventSummaryDTO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long eventId;

    private Long sequenceNo;
    private String eventType;
    private String eventFingerprint;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long previousEventId;

    private String previousFingerprint;
    private String decisionFingerprint;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Date appliedAt;
}
