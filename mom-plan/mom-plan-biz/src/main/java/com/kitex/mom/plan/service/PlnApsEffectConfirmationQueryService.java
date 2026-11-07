package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.plan.entity.PlnApsCaseEntity;
import com.kitex.mom.plan.entity.PlnApsCaseSourceEntity;
import com.kitex.mom.plan.entity.PlnSchedulingEffectActionEntity;
import com.kitex.mom.plan.entity.PlnSchedulingEffectReviewEntity;
import com.kitex.mom.plan.mapper.PlnApsCaseMapper;
import com.kitex.mom.plan.mapper.PlnApsCaseSourceMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingEffectActionMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingEffectReviewMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;

/**
 * 原效果偏差行动的确认结果查询。只证明此行动当前已确认，不冒充某一客户端请求成功，
 * 不随最新观察改写历史确认，不触发建案、求解、审批或下达。
 */
@Service
@RequiredArgsConstructor
public class PlnApsEffectConfirmationQueryService {
    private final PlnSchedulingEffectReviewMapper reviews;
    private final PlnSchedulingEffectActionMapper actions;
    private final PlnApsCaseMapper cases;
    private final PlnApsCaseSourceMapper sources;
    private final RemoteMdmFactoryAccessService factories;

    /** 行动和案件主来源使用同一只读快照；外来行动先在原回评范围内定位。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Confirmation read(Long reviewId, Long actionId, String expectedFingerprint) {
        require(reviewId != null && reviewId > 0 && actionId != null && actionId > 0
                        && expectedFingerprint != null && expectedFingerprint.matches("[a-f0-9]{64}"), "请从原效果偏差行动核对确认结果");
        var actor = SecurityUtils.getUser();
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        require(actor != null && actor.getId() != null && actor.getId() > 0 && tenant != null && tenant > 0
                        && Objects.equals(actor.getTenantId(), tenant) && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                        && factory != null && !factory.isBlank(), "效果偏差确认结果须在实名租户工厂内读取");
        R<Boolean> access = factories.check(new MdmFactoryAccessCheck().setUserId(actor.getId()).setFactoryCode(factory).setRequiredPermission("read"));
        require(access != null && access.isOk() && Boolean.TRUE.equals(access.getData()), "当前人员没有该工厂READ权限或授权无法核验");
        var review = reviews.selectOne(new LambdaQueryWrapper<PlnSchedulingEffectReviewEntity>()
                .eq(PlnSchedulingEffectReviewEntity::getId, reviewId).eq(PlnSchedulingEffectReviewEntity::getTenantId, tenant)
                .eq(PlnSchedulingEffectReviewEntity::getFactoryCode, factory));
        require(review != null, "当前工厂未找到原效果回评");
        var action = actions.selectOne(new LambdaQueryWrapper<PlnSchedulingEffectActionEntity>()
                .eq(PlnSchedulingEffectActionEntity::getId, actionId).eq(PlnSchedulingEffectActionEntity::getReviewId, reviewId)
                .eq(PlnSchedulingEffectActionEntity::getTenantId, tenant).eq(PlnSchedulingEffectActionEntity::getFactoryCode, factory));
        require(action != null && expectedFingerprint.equals(action.getEffectFingerprint())
                        && Set.of("PENDING", "CONFIRMED", "RESOLVED", "SUPERSEDED").contains(Objects.requireNonNullElse(action.getActionStatus(), "")),
                "原偏差行动、指纹或状态无法核对，请保留原请求核查");
        CaseReference target = null;
        if ("CONFIRMED".equals(action.getActionStatus())) {
            require(action.getConfirmedCaseId() != null && action.getConfirmedCaseId() > 0
                            && action.getConfirmedBy() != null && !action.getConfirmedBy().isBlank() && action.getConfirmedTime() != null,
                    "原偏差确认记录不完整，请回原回评核查");
            var apsCase = cases.selectOne(new LambdaQueryWrapper<PlnApsCaseEntity>()
                    .eq(PlnApsCaseEntity::getId, action.getConfirmedCaseId()).eq(PlnApsCaseEntity::getTenantId, tenant)
                    .eq(PlnApsCaseEntity::getFactoryCode, factory));
            require(apsCase != null && Objects.equals(apsCase.getEffectReviewId(), reviewId)
                            && "EXECUTION_DEVIATION_REPLAN".equals(apsCase.getCaseType()), "确认结果中的APS案件与原回评不一致");
            var primary = sources.selectList(new LambdaQueryWrapper<PlnApsCaseSourceEntity>()
                    .eq(PlnApsCaseSourceEntity::getCaseId, apsCase.getId()).eq(PlnApsCaseSourceEntity::getSourceRole, "PRIMARY")
                    .eq(PlnApsCaseSourceEntity::getTenantId, tenant).eq(PlnApsCaseSourceEntity::getFactoryCode, factory).last("LIMIT 2"));
            require(primary.size() == 1 && "EFFECT_REVIEW".equals(primary.get(0).getSourceType())
                            && Objects.equals(primary.get(0).getSourceId(), reviewId), "原APS案件主来源关系无法核对");
            target = new CaseReference(apsCase.getId().toString(), apsCase.getCaseNo(), apsCase.getCaseStatus());
        } else require(action.getConfirmedCaseId() == null && action.getConfirmedBy() == null && action.getConfirmedTime() == null,
                "未确认的原行动含有确认信息，请保留原请求核查");
        return new Confirmation("APS_EFFECT_CONFIRMATION_V1", tenant.toString(), factory, reviewId.toString(), actionId.toString(),
                expectedFingerprint, action.getActionStatus(), action.getConfirmedBy(), action.getConfirmedTime(), target);
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new CheckedException(message);
    }

    /** 案件是原行动的结果，不代表某一请求或当前用户完成审批。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CaseReference(String id, String no, String status) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Confirmation(String version, String tenantId, String factoryCode, String reviewId, String actionId,
                               String effectFingerprint, String state, String confirmedBy,
                               @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS") LocalDateTime confirmedAt,
                               CaseReference apsCase) {
    }
}
