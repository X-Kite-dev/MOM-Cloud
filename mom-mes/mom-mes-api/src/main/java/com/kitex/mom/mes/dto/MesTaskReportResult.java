package com.kitex.mom.mes.dto;

import com.kitex.mom.mes.entity.MesTaskReportEntity;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 报工模式执行结果。
 */
@Data
@Accessors(chain = true)
public class MesTaskReportResult {

    private MesTaskReportEntity report;

    private String reportMessage;

    private String wrapMessage;
}
