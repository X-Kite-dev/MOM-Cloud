package com.kitex.mom.qms.agent.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.qms.agent.dto.QmsExperienceReferenceCommand;
import com.kitex.mom.qms.entity.QmsCapaExperienceReferenceEntity;

/**
 * 当前CAPA显式引用与历史回执接口，不提供自动根因/措施写入。
 */
public interface QmsExperienceReferenceService extends IService<QmsCapaExperienceReferenceEntity> {
    QmsCapaExperienceReferenceEntity reference(QmsExperienceReferenceCommand command);

    Page<QmsCapaExperienceReferenceEntity> page(Long capaId, long current, long size);

    /** 显式周期过滤；未指定时保持全部历史周期。 */
    Page<QmsCapaExperienceReferenceEntity> page(Long capaId, long current, long size, Integer cycleNo);

    /** 按原引用周期读取案件措施与业务验证，不改引用、CAPA或经验状态。 */
    com.kitex.mom.qms.agent.dto.QmsExperienceFollowup followup(Long referenceId, Long capaId, long current, long size);

    Object request(String requestId);

    Object seal(String requestId);

    /**
     * 原调用人受控核账；取消与正式写入同锁，不披露历史质量正文。
     */
    java.util.Map<String, Object> reconcile(String requestId, boolean cancel);
}
