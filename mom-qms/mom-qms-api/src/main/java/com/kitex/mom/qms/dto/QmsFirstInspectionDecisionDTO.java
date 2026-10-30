package com.kitex.mom.qms.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** 最早留存判定的追溯依据，不认证原始首检完整性或输出一次合格率。 */
@Data
@Accessors(chain = true)
public class QmsFirstInspectionDecisionDTO {
    private String inspectionType;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;

    @Schema(description = "AVAILABLE/MISSING/INCOMPLETE/AMBIGUOUS")
    private String availability;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long historyId;

    @Schema(description = "最早留存判定时刻，ISO8601 UTC格式，含毫秒和明确的Z偏移")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Date judgedAt;

    @Schema(description = "最早留存判定，非认证原始首检；记录不完整或冲突时为null")
    private Boolean qualified;

    @Schema(description = "旧历史无法证明更早判定未丢失，不能直接作为一次合格率资格")
    private Boolean firstPassEligible = Boolean.FALSE;

    private String metricDefinition = "按最早留存RESULT记录追溯；不能证明更早判定未丢失，不直接计算认证一次合格率";

    private Integer inspectionQuantity;
    private String evidenceRef;
    private List<String> dataGaps = new ArrayList<>();
}
