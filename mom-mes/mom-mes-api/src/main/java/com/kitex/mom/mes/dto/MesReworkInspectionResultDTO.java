package com.kitex.mom.mes.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * QMS返工复检结论回传MES，用于按数量解除隔离或转拒绝。
 */
@Data
@Accessors(chain = true)
public class MesReworkInspectionResultDTO {
    private String requestId;
    private Long qmsTaskId;
    private String inspectionType;
    private String result;
    private Integer totalNum;
    private Integer usableNum;
    private Integer handleNum;
    private String handleResult;
}
