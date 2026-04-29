package com.kitex.mom.qms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.qms.entity.QmsInspectionProcedureHistoryEntity;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskEntity;

import java.util.List;

/**
 * 质检任务历史记录
 *
 * @author kitex
 * @date 2022/12/9
 */
public interface InspectionProcedureHistoryService extends IService<QmsInspectionProcedureHistoryEntity> {


    /**
     * 创建任务
     */
    void create(QmsInspectionProcedureTaskEntity task);

    /**
     * 追加任务
     */
    void append(QmsInspectionProcedureTaskEntity task, Integer num);

    /**
     * 质检操作
     */
    void check(QmsInspectionProcedureTaskEntity task);

    /**
     * 作废
     */
    void canceled(QmsInspectionProcedureTaskEntity task, String reason);

    /**
     * 免检
     */
    void exemption(QmsInspectionProcedureTaskEntity task);

    /**
     * 根据任务id 查询质检历史列表
     *
     * @return
     */
    List<QmsInspectionProcedureHistoryEntity> findAllByTaskId(Long taskId);

    /**
     * 质检任务历史详情
     */
    QmsInspectionProcedureHistoryEntity findById(Long id);

}
