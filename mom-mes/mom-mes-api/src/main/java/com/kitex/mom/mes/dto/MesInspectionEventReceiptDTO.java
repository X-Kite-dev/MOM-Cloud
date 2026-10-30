package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * 只确认不可变原文收件；不代表质量放行、数量应用或首次合格率资格。
 */
@Data
@Accessors(chain = true)
public class MesInspectionEventReceiptDTO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long eventId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;

    private Long sequenceNo;
    private String fingerprint;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Date receivedAt;

    private String status;
    private Boolean applied;
}
