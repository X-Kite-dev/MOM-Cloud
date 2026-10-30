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

/**
 * 只报告准入/取消事实；RESERVED不是QMS改判或MES应用许可。
 */
@Data
@Accessors(chain = true)
public class MesInspectionRevisionAdmissionDTO {
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
    @JsonSerialize(using = ToStringSerializer.class)
    private Long baseEventId;
    private String baseFingerprint;
    private String intentFingerprint;
    private String status;
    private boolean held;
    private boolean applied;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Date reservedAt;
    private String admissionFingerprint;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Date canceledAt;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long canceledBy;
    private String cancelReason;

    public MesInspectionRevisionAdmissionDTO() {
    }

    /**
     * 网络/永久原件必须声明真实JSON布尔；保留原getter/setter和默认构造器兼容。
     */
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public MesInspectionRevisionAdmissionDTO(
            @JsonProperty(value = "held", required = true) JsonNode held,
            @JsonProperty(value = "applied", required = true) JsonNode applied) {
        if (held == null || !held.isBoolean() || applied == null || !applied.isBoolean())
            throw new IllegalArgumentException("MES修订回执须明确声明held/applied布尔值");
        this.held = held.booleanValue();
        this.applied = applied.booleanValue();
    }
}
