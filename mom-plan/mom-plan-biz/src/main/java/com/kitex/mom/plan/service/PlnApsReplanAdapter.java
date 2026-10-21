package com.kitex.mom.plan.service;

import com.kitex.mom.plan.dto.PlnApsCaseDTO;
import com.kitex.mom.plan.dto.PlnApsReplanDTO;

/**
 * 既有缺料、资源、质量、插单和执行偏差重排到统一 APS 案件的适配器。
 *
 * <p>专用领域服务仍是各自约束、候选、发布和回退的事实根；本适配器只建立案件引用并统一展示，
 * 不复制或绕过专用服务的 A2/A3。</p>
 */
public interface PlnApsReplanAdapter {

    /** 从专用重排案件幂等启动统一 APS 案件。 */
    PlnApsCaseDTO launchFromReplanSource(PlnApsReplanDTO.LaunchCommand command);

    /** 从执行效果回评幂等启动待确认的执行偏差案件。 */
    PlnApsCaseDTO launchFromEffectReview(Long reviewId, String requestId);

    /** 查询专用事实在统一案件中的只读投影。 */
    PlnApsReplanDTO.Projection projection(Long apsCaseId);
}
