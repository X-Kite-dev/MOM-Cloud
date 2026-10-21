package com.kitex.mom.plan.task;

import com.kitex.mom.plan.service.PlnSchedulingEffectReviewService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlnSchedulingEffectReviewJob {
    private final PlnSchedulingEffectReviewService service;

    /**
     * 建议每10分钟执行；自动补建新发布情景并滚动采集尚未完成的MES执行事实。
     */
    @XxlJob("pln_scheduling_effect_review")
    public void refresh() {
        int refreshed = service.refreshDue(100);
        log.info("APS候选执行效果回评完成，本轮处理情景数={}", refreshed);
    }
}
