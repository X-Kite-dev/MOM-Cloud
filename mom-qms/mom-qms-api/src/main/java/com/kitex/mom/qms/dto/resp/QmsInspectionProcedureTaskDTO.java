package com.kitex.mom.qms.dto.resp;

import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskDetailEntity;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskEntity;
import lombok.Data;

import java.util.List;

/**
 * 详情
 */
@Data
public class QmsInspectionProcedureTaskDTO {

    private QmsInspectionProcedureTaskEntity task;

    private List<QmsInspectionProcedureTaskDetailEntity> details;

    private List<QmsCheckClassifyDTO> classify;
}
