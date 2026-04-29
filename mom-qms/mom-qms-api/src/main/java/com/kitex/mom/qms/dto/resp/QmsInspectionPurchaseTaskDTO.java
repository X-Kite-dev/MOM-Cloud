package com.kitex.mom.qms.dto.resp;

import com.kitex.mom.qms.entity.QmsInspectionPurchaseTaskDetailEntity;
import com.kitex.mom.qms.entity.QmsInspectionPurchaseTaskEntity;
import lombok.Data;

import java.util.List;

/**
 * 详情
 */
@Data
public class QmsInspectionPurchaseTaskDTO {

    private QmsInspectionPurchaseTaskEntity task;

    private List<QmsInspectionPurchaseTaskDetailEntity> details;

    private List<QmsCheckClassifyDTO> classify;
}
