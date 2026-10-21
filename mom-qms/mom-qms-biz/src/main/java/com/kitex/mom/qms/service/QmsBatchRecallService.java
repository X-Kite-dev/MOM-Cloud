package com.kitex.mom.qms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.qms.dto.QmsBatchRecallDTO;
import com.kitex.mom.qms.entity.QmsBatchRecallEntity;

/**
 * 批次召回候选的申请、异人复核、WMS隔离、通知证据和独立验证闭环。
 */
public interface QmsBatchRecallService extends IService<QmsBatchRecallEntity> {
    QmsBatchRecallEntity propose(QmsBatchRecallDTO.ProposeCommand command);

    QmsBatchRecallEntity review(QmsBatchRecallDTO.ReviewCommand command);

    QmsBatchRecallEntity execute(QmsBatchRecallDTO.ExecuteCommand command);

    QmsBatchRecallEntity recordNotification(QmsBatchRecallDTO.NotificationCommand command);

    QmsBatchRecallEntity verify(QmsBatchRecallDTO.VerifyCommand command);

    QmsBatchRecallDTO.Detail detail(Long id);
}
