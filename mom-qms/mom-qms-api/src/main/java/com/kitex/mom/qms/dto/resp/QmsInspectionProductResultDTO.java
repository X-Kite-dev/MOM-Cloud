package com.kitex.mom.qms.dto.resp;

import com.kitex.mom.qms.dto.ReportListRespDTO;
import com.kitex.mom.qms.entity.QmsInspectionProductTaskEntity;
import com.kitex.mom.qms.entity.QmsQualityStandardEntity;
import lombok.Data;

/**
 * 详情
 */
@Data
public class QmsInspectionProductResultDTO {

    private QmsInspectionProductTaskEntity task;

    private ReportListRespDTO reportList;

    private QmsQualityStandardEntity standard;
}
