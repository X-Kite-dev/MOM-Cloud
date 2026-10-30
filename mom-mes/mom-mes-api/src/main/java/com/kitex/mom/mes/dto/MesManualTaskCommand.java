package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.kitex.mom.mes.serialization.MesNativeQuantitySerializer;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 手工建单仅接受输入事实，不能提交计划合同或替代工程正文。
 */
@Data
@Accessors(chain = true)
public class MesManualTaskCommand {
    private String requestId;
    private String expectedTenantId;
    private String expectedFactoryCode;
    private String expectedActorId;
    private String matCode;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long engineeringReleaseId;

    private String engineeringContentHash;

    @JsonSerialize(using = MesNativeQuantitySerializer.class)
    private BigDecimal quantity;

    private String type;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime planStartDate;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime planEndDate;

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("手工建单不接受额外字段：" + name);
    }
}
