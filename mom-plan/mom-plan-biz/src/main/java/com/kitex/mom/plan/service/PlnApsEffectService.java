package com.kitex.mom.plan.service;

import com.kitex.mom.plan.dto.PlnApsReplanDTO;

/**
 * 已发布排程效果偏差到统一 APS 待确认案件的判定服务。
 */
public interface PlnApsEffectService {

    /**
     * 使用效果观察记录自身的偏差容差判定是否需要建立重排案件。
     * 未达到阈值只返回结论，不创建案件；达到阈值也只创建待确认案件，不自动选案或发布。
     */
    PlnApsReplanDTO.EffectCaseResult launchFromEffectReview(Long reviewId,
                                                            PlnApsReplanDTO.EffectLaunchCommand command);
}
