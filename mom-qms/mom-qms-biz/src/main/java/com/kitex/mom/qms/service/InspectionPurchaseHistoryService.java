package com.kitex.mom.qms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.qms.entity.QmsInspectionPurchaseHistoryEntity;
import com.kitex.mom.qms.entity.QmsInspectionPurchaseTaskEntity;

import java.util.List;

/**
 * 质检任务历史记录
 *
 * @author kitex
 * @date 2022/12/9
 */
public interface InspectionPurchaseHistoryService extends IService<QmsInspectionPurchaseHistoryEntity> {


    /**
     * 创建任务
     */
    void create(QmsInspectionPurchaseTaskEntity task);

    /**
     * 追加任务
     */
    void append(QmsInspectionPurchaseTaskEntity task, Integer num);

    /**
     * 质检操作
     */
    void check(QmsInspectionPurchaseTaskEntity task);

    /**
     * 作废
     */
    void canceled(QmsInspectionPurchaseTaskEntity task, String reason);

    /**
     * 免检
     */
    void exemption(QmsInspectionPurchaseTaskEntity task);

    /**
     * 根据任务id 查询质检历史列表
     *
     * @return
     */
    List<QmsInspectionPurchaseHistoryEntity> findAllByTaskId(Long taskId);

    /**
     * 质检任务历史详情
     */
    QmsInspectionPurchaseHistoryEntity findById(Long id);

}
