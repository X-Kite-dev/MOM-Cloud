package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.entity.MesQmsInspectionCommandEntity;
import com.kitex.mom.qms.dto.inspection.InspectionProcedureCreateParam;

/**
 * MES调用QMS创建工序质检任务前后的永久出站账。
 */
public interface MesQmsInspectionCommandService extends IService<MesQmsInspectionCommandEntity> {
    /**
     * 独立登记完整原命令；相同原号只允许完全相同参数。
     */
    MesQmsInspectionCommandEntity register(InspectionProcedureCreateParam command, String factoryCode);

    /**
     * 独立提交真实远端调用边界；返回已确认任务时调用者直接回放。
     */
    MesQmsInspectionCommandEntity beginAttempt(InspectionProcedureCreateParam command, String factoryCode);

    /**
     * 独立保存QMS明确返回的唯一任务主键。
     */
    MesQmsInspectionCommandEntity confirm(InspectionProcedureCreateParam command, String factoryCode, Long qmsTaskId);

    /**
     * 只记录异常摘要，不把超时或错误响应解释为远端未创建。
     */
    void recordFailure(InspectionProcedureCreateParam command, String factoryCode, Throwable failure);
}
