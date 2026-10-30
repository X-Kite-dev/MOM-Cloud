package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.mom.mes.entity.MesExecutionTemplateEntity;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 当前同编码/范围版本切换的只读核对结果；提交时仍须重新检查两行修订及发布条件。
 */
@Data
public class MesExecutionTemplateReplacementDTO {
    private String version = "MES_EXECUTION_TEMPLATE_REPLACEMENT_V1";
    private String tenantId;
    private String factoryCode;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime evaluatedAt;
    private MesExecutionTemplateEntity source;
    private MesExecutionTemplateEntity draft;
}
