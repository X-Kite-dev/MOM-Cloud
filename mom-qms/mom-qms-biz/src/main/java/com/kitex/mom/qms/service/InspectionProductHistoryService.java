package com.kitex.mom.qms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.qms.entity.QmsInspectionProductHistoryEntity;
import com.kitex.mom.qms.entity.QmsInspectionProductTaskEntity;

import java.util.List;

/**
 * 成品质检任务历史记录
 *
 * @author kitex
 * @date 2022/12/9
 */
public interface InspectionProductHistoryService extends IService<QmsInspectionProductHistoryEntity> {


    /**
     * 创建任务
     */
    void create(QmsInspectionProductTaskEntity task);

    /**
     * 追加任务
     */
    void append(QmsInspectionProductTaskEntity task, Integer num);

    /**
     * 质检操作
     */
    void check(QmsInspectionProductTaskEntity task);

    /**
     * 作废
     */
    void canceled(QmsInspectionProductTaskEntity task, String reason);

    /**
     * 免检
     */
    void exemption(QmsInspectionProductTaskEntity task);

    /**
     * 根据任务id 查询质检历史列表
     *
     * @return
     */
    List<QmsInspectionProductHistoryEntity> findAllByTaskId(Long taskId);

    /**
     * 质检任务历史详情
     */
    QmsInspectionProductHistoryEntity findById(Long id);

}
