package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 自动报工事件受理结果，重复提交返回同一结果。
 */
@Data
@Accessors(chain = true)
public class MesAutoReportResultDTO {
    @Schema(description = "原事件ID，文本序列化")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long eventId;
    @Schema(description = "永久来源事件号")
    private String sourceEventId;
    @Schema(description = "原事件处理状态；受理不等于报工成功")
    private String status;
    @Schema(description = "当前原事件处理结论")
    private String message;
    @Schema(description = "原成功报工ID，文本序列化")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long reportId;
    @Schema(description = "成功归属派工")
    private String arrangeCode;
}
