package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.plan.entity.PlnSchedulingEffectActionEntity;
import com.kitex.mom.plan.entity.PlnSchedulingEffectObservationEntity;
import com.kitex.mom.plan.entity.PlnSchedulingEffectReviewEntity;

/**
 * 将MES执行偏差归一为可追溯的待确认行动，案件创建仍由真实登录人触发。
 */
public interface PlnSchedulingEffectActionService extends IService<PlnSchedulingEffectActionEntity> {

    /**
     * 在效果观察所在事务中更新待确认行动；未越阈值时返回空。
     */
    PlnSchedulingEffectActionEntity reconcile(PlnSchedulingEffectReviewEntity review,
                                              PlnSchedulingEffectObservationEntity observation);

    /**
     * 查询当前回评仍可确认的行动，不把旧观察当作当前事实。
     */
    PlnSchedulingEffectActionEntity pending(PlnSchedulingEffectReviewEntity review);

    /**
     * 已由受权人创建或复用案件后，原子登记人工确认及案件引用。
     */
    void confirm(PlnSchedulingEffectActionEntity action, Long caseId, String actor);
}
