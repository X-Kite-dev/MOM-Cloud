package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.mom.plan.dto.PlnSchedulingEffectReviewDTO;
import com.kitex.mom.plan.entity.PlnSchedulingEffectReviewEntity;

public interface PlnSchedulingEffectReviewService {
    Page<PlnSchedulingEffectReviewEntity> page(SearchPage page, PlnSchedulingEffectReviewDTO.Query query);

    PlnSchedulingEffectReviewDTO.Detail detail(Long reviewId);

    /**
     * 按排产情景查询当前回评及全部观察历史；尚未形成回评时返回空。
     */
    PlnSchedulingEffectReviewDTO.Detail detailByScenarioId(Long scenarioId);

    PlnSchedulingEffectReviewDTO.Detail capture(PlnSchedulingEffectReviewDTO.CaptureCommand command);

    int refreshDue(int limit);
}
