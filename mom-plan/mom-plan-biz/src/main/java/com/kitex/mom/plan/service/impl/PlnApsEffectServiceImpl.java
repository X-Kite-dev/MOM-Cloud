package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.plan.dto.PlnApsCaseDTO;
import com.kitex.mom.plan.dto.PlnApsReplanDTO;
import com.kitex.mom.plan.entity.PlnSchedulingEffectActionEntity;
import com.kitex.mom.plan.entity.PlnSchedulingEffectObservationEntity;
import com.kitex.mom.plan.entity.PlnSchedulingEffectReviewEntity;
import com.kitex.mom.plan.mapper.PlnSchedulingEffectObservationMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingEffectReviewMapper;
import com.kitex.mom.plan.service.PlnApsEffectService;
import com.kitex.mom.plan.service.PlnApsCaseService;
import com.kitex.mom.plan.service.PlnApsReplanAdapter;
import com.kitex.mom.plan.service.PlnSchedulingEffectActionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** 效果回评偏差转 APS 待确认案件的确定性判定实现。 */
@Service
@RequiredArgsConstructor
public class PlnApsEffectServiceImpl implements PlnApsEffectService {

    private final PlnSchedulingEffectReviewMapper reviewMapper;
    private final PlnSchedulingEffectObservationMapper observationMapper;
    private final PlnApsReplanAdapter replanAdapter;
    private final PlnApsCaseService caseService;
    private final PlnSchedulingEffectActionService actionService;
    private final RemoteMdmFactoryAccessService factoryAccessService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnApsReplanDTO.EffectCaseResult launchFromEffectReview(
            Long reviewId, PlnApsReplanDTO.EffectLaunchCommand command) {
        String requestId = command == null ? null : command.getRequestId();
        require(reviewId != null && reviewId > 0 && StrUtil.isNotBlank(requestId),
                "效果偏差建案的回评主键和请求号不能为空");
        require(StrUtil.isNotBlank(command.getExpectedActionId())
                        && StrUtil.isNotBlank(command.getExpectedEffectFingerprint()),
                "请刷新效果回评并确认当前偏差行动后再建案");
        MomUser actor = SecurityUtils.getUser();
        require(actor != null && actor.getId() != null && StrUtil.isNotBlank(actor.getUsername()),
                "效果偏差建案必须由真实登录人员确认");
        PlnSchedulingEffectReviewEntity review = reviewMapper.selectById(reviewId);
        require(review != null, "排程效果回评不存在");
        require(review.getTenantId() != null && review.getTenantId() > 0
                        && StrUtil.isNotBlank(review.getFactoryCode())
                        && Objects.equals(review.getTenantId(), TenantContextHolder.getTenantId())
                        && Objects.equals(review.getFactoryCode(), FactoryContextHolder.getFactoryCode())
                        && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()),
                "效果回评不属于当前租户工厂");
        R<Boolean> access = factoryAccessService.check(new MdmFactoryAccessCheck()
                .setUserId(actor.getId()).setFactoryCode(review.getFactoryCode()).setRequiredPermission("write"));
        require(access != null && access.isOk() && Boolean.TRUE.equals(access.getData()),
                "当前人员缺少该工厂写权限或MDM工厂核验不可用");
        // 与后台采集使用相同的情景锁；获取锁之后必须重新读取当前观察，不能确认弹框打开前的旧证据。
        reviewMapper.lockKey("SCENARIO:" + review.getScenarioId());
        review = reviewMapper.selectById(reviewId);
        require(review != null && Objects.equals(review.getTenantId(), TenantContextHolder.getTenantId())
                        && Objects.equals(review.getFactoryCode(), FactoryContextHolder.getFactoryCode()),
                "效果回评已变化，请刷新后重试");
        PlnSchedulingEffectObservationEntity observation = review.getCurrentObservationId() == null
                ? null : observationMapper.selectById(review.getCurrentObservationId());
        require(observation != null, "排程效果回评尚无可核验观察记录");
        int tolerance = Objects.requireNonNullElse(observation.getToleranceMinutes(), 30);
        PlnSchedulingEffectActionEntity action = actionService.reconcile(review, observation);
        require(action != null && Objects.equals(action.getId().toString(), command.getExpectedActionId().trim())
                        && Objects.equals(action.getEffectFingerprint(), command.getExpectedEffectFingerprint().trim()),
                "效果偏差行动或MES事实已变化，请刷新后重新确认");
        if ("CONFIRMED".equals(action.getActionStatus())) {
            require(action.getConfirmedCaseId() != null, "已确认的效果偏差行动缺少APS案件引用");
            return result(true, "同一效果指纹已由人工确认，复用原APS案件", tolerance,
                    reviewId, caseService.detail(action.getConfirmedCaseId()));
        }
        require("PENDING".equals(action.getActionStatus()), "效果偏差行动状态已变化，请刷新后重试");
        PlnApsCaseDTO apsCase = replanAdapter.launchFromEffectReview(reviewId, requestId.trim());
        require(Objects.equals(apsCase.getEffectReviewId(), reviewId.toString()),
                "创建的APS案件未绑定当前效果回评，不能确认偏差行动");
        actionService.confirm(action, Long.valueOf(apsCase.getId()), actor.getUsername());
        return result(true, action.getTriggerReason() + "；已由当前计划员确认建案，未自动A2/A3",
                tolerance, reviewId, apsCase);
    }

    private PlnApsReplanDTO.EffectCaseResult result(boolean triggered, String reason, int tolerance,
                                                     Long reviewId, PlnApsCaseDTO apsCase) {
        return new PlnApsReplanDTO.EffectCaseResult().setTriggered(triggered).setReason(reason)
                .setToleranceMinutes(tolerance).setEffectReviewId(reviewId.toString()).setApsCase(apsCase);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
