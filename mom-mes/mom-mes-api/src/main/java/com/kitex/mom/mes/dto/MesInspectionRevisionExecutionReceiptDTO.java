package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

@Data
@Accessors(chain = true)
public class MesInspectionRevisionExecutionReceiptDTO {
    private String contractVersion;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long requestId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;
    private String factoryCode;
    private String taskCode;
    private String produceTaskCode;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long inspectionId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long genRecordId;
    private String intentFingerprint;
    private String status;
    private boolean held;
    private boolean applied;
    private String sealFingerprint;
    private String outcomeFingerprint;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long eventId;
    private String eventFingerprint;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Date sealedAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Date resolvedAt;
    private MesInspectionRevisionAdmissionDTO cancellation;

    public MesInspectionRevisionExecutionReceiptDTO() {
    }

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public MesInspectionRevisionExecutionReceiptDTO(
            @JsonProperty(value = "held", required = true) JsonNode held,
            @JsonProperty(value = "applied", required = true) JsonNode applied) {
        if (held == null || !held.isBoolean() || applied == null || !applied.isBoolean())
            throw new IllegalArgumentException("执行回执须明确布尔声明");
        this.held = held.booleanValue();
        this.applied = applied.booleanValue();
    }
}
