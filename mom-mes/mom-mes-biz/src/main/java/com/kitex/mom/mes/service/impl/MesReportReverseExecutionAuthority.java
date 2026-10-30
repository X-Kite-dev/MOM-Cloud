package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.entity.MesReportReverseRequestEntity;
import com.kitex.mom.mes.entity.MesReportReverseScopeEntity;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.Objects;

/**
 * 异岗复核已重采完整范围后的本地事务执行资格，不是可序列化到HTTP的审批令牌。
 *
 * <p>只能由同包复核实现短期建立；私有构造、线程隔离、finally清理，不能跨请求/线程复用。
 * 工作者每步仍核定原申请、冻结scope、原号和实名复核人；数据库在提交时核验批准履历。
 * 这不替代数据库权限或真实库存/QMS逆向，不使用可由客户端设置的GUC作为授权。</p>
 */
final class MesReportReverseExecutionAuthority {

    private static final ThreadLocal<MesReportReverseExecutionAuthority> CURRENT = new ThreadLocal<>();
    final Long requestId;
    final String evidenceFingerprint;
    final String evidenceSnapshot;
    final String reviewRequestId;
    final String reviewFingerprint;
    final Long reviewerId;
    final String reviewerCode;
    final Map<Long, MesReportReverseScopeEntity> scopes;

    private MesReportReverseExecutionAuthority(MesReportReverseRequestEntity request, String reviewRequestId,
                                               String reviewFingerprint,
                                               Map<Long, MesReportReverseScopeEntity> scopes) {
        this.requestId = request.getId();
        this.evidenceFingerprint = request.getEvidenceFingerprint();
        this.evidenceSnapshot = request.getEvidenceSnapshot();
        this.reviewRequestId = reviewRequestId;
        this.reviewFingerprint = reviewFingerprint;
        this.reviewerId = SecurityUtils.getUser().getId();
        this.reviewerCode = SecurityUtils.getUser().getUsername();
        this.scopes = Map.copyOf(scopes);
    }

    /**
     * 复核服务已核对完整原指纹/逐报告范围后，在现有事务中执行，不能建立嵌套资格。
     */
    static void runApproved(MesReportReverseRequestEntity request, String reviewRequestId,
                            String reviewFingerprint, Map<Long, MesReportReverseScopeEntity> scopes,
                            Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || CURRENT.get() != null
                || request == null || !"SUBMITTED".equals(request.getStatus())
                || SecurityUtils.getUser() == null
                || Objects.equals(request.getApplicantId(), SecurityUtils.getUser().getId())
                || Objects.equals(request.getApplicantCode(), SecurityUtils.getUser().getUsername())) {
            throw new CheckedException("节点补偿只能在已完整取证的实名异岗复核事务中执行");
        }
        CURRENT.set(new MesReportReverseExecutionAuthority(request, reviewRequestId, reviewFingerprint, scopes));
        try {
            action.run();
        } finally {
            CURRENT.remove();
        }
    }

    static MesReportReverseExecutionAuthority requireCurrent() {
        MesReportReverseExecutionAuthority value = CURRENT.get();
        if (value == null || !TransactionSynchronizationManager.isActualTransactionActive()
                || SecurityUtils.getUser() == null
                || !Objects.equals(value.reviewerId, SecurityUtils.getUser().getId())
                || !Objects.equals(value.reviewerCode, SecurityUtils.getUser().getUsername())) {
            throw new CheckedException("动态节点不能通过旧直接冲销入口执行，请使用异岗审批");
        }
        return value;
    }
}
