package com.kitex.mom.qms.dto.resp;

import com.kitex.mom.qms.dto.ReportListRespDTO;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskEntity;
import com.kitex.mom.qms.entity.QmsQualityStandardEntity;
import lombok.Data;

/**
 * 详情
 */
@Data
public class QmsInspectionProcedureResultDTO {

    private QmsInspectionProcedureTaskEntity task;

    private ReportListRespDTO reportList;

    private QmsQualityStandardEntity standard;
}
