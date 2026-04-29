package com.kitex.mom.qms.dto.resp;

import com.kitex.mom.qms.entity.QmsInspectionProductTaskDetailEntity;
import com.kitex.mom.qms.entity.QmsInspectionProductTaskEntity;
import lombok.Data;

import java.util.List;

/**
 * 详情
 */
@Data
public class QmsInspectionProductTaskDTO {

    private QmsInspectionProductTaskEntity task;

    private List<QmsInspectionProductTaskDetailEntity> details;

    private List<QmsCheckClassifyDTO> classify;
}
