package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.entity.MesChangePointImprovementEntity;
import com.kitex.mom.mes.mapper.MesChangePointImprovementMapper;
import com.kitex.mom.qms.api.feign.RemoteQmsCapaService;
import com.kitex.mom.qms.dto.QmsCapaExternalCreateCommand;
import com.kitex.mom.qms.dto.QmsCapaLinkDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * MES变化点改善来源与QMS CAPA之间的可靠链接协调器。
 *
 * <p>MES先提交冻结基线，再调用QMS；QMS请求号永久稳定，可在响应丢失后安全重试。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MesChangePointImprovementCoordinator {

    private static final Set<String> TERMINAL_LINK = Set.of("CLOSED", "CANCELLED");
    private final MesChangePointImprovementMapper mapper;
    private final MesChangePointImprovementServiceImpl service;
    private final RemoteQmsCapaService remoteQmsCapaService;
    private final PlatformTransactionManager transactionManager;

    public void attempt(Long id) {
        MesChangePointImprovementEntity record = service.getDetail(id);
        if (record.getQmsCapaId() != null) {
            refresh(id);
            return;
        }
        try {
            var response = remoteQmsCapaService.createExternal(toCommand(record));
            if (response == null || !response.isOk() || response.getData() == null) {
                throw new CheckedException(response == null ? "QMS无响应" : response.getMsg());
            }
            persistLink(id, response.getData(), "LINK", "已创建并链接QMS CAPA");
        } catch (Exception exception) {
            log.error("变化点改善来源已冻结但创建QMS CAPA失败, improvementId={}", id, exception);
            persistFailure(id, exception);
        }
    }

    public void retry(Long id) {
        MesChangePointImprovementEntity record = service.getDetail(id);
        if (record.getQmsCapaId() != null) {
            refresh(id);
            return;
        }
        if (!Set.of("PENDING", "FAILED_UNKNOWN").contains(record.getLinkStatus())) {
            throw new CheckedException("当前改善来源不在可重试链接状态");
        }
        attempt(id);
    }

    public void refresh(Long id) {
        MesChangePointImprovementEntity record = service.getDetail(id);
        if (record.getQmsCapaId() == null) throw new CheckedException("尚未取得QMS CAPA主键，请先重试创建");
        try {
            var response = remoteQmsCapaService.getLink(record.getQmsCapaId());
            if (response == null || !response.isOk() || response.getData() == null) {
                throw new CheckedException(response == null ? "QMS无响应" : response.getMsg());
            }
            persistLink(id, response.getData(), "REFRESH", "已同步QMS CAPA当前阶段");
        } catch (Exception exception) {
            log.error("同步QMS CAPA状态失败, improvementId={}, capaId={}", id, record.getQmsCapaId(), exception);
            throw exception instanceof CheckedException checked ? checked
                    : new CheckedException("同步QMS CAPA状态失败：" + safeMessage(exception));
        }
    }

    private void persistLink(Long id, QmsCapaLinkDTO link, String operationType, String result) {
        TransactionTemplate transaction = requiresNew();
        transaction.executeWithoutResult(status -> {
            MesChangePointImprovementEntity record = lock(id);
            validateLink(record, link);
            String before = record.getLinkStatus();
            String linkStatus = "CLOSED".equals(link.getStatus()) ? "CLOSED"
                    : "CANCELLED".equals(link.getStatus()) ? "CANCELLED" : "LINKED";
            record.setQmsCapaId(link.getCapaId()).setQmsCapaNo(link.getCapaNo())
                    .setQmsCapaStatus(link.getStatus()).setLinkStatus(linkStatus)
                    .setLinkResult(result + "：" + link.getCapaNo() + "/" + link.getStatus())
                    .setLinkTime(LocalDateTime.now());
            if (mapper.updateById(record) != 1) throw new CheckedException("CAPA链接结果保存失败");
            service.saveOperation(record, UUID.randomUUID().toString(), operationType, before, linkStatus,
                    record.getLinkResult());
        });
    }

    private void persistFailure(Long id, Exception exception) {
        try {
            requiresNew().executeWithoutResult(status -> {
                MesChangePointImprovementEntity record = lock(id);
                if (record.getQmsCapaId() != null || TERMINAL_LINK.contains(record.getLinkStatus())) return;
                String before = record.getLinkStatus();
                record.setLinkStatus("FAILED_UNKNOWN")
                        .setLinkResult("QMS创建失败或结果不明；MES基线不撤销，按永久请求号重试：" + safeMessage(exception));
                if (mapper.updateById(record) != 1) throw new CheckedException("CAPA失败结果保存失败");
                service.saveOperation(record, UUID.randomUUID().toString(), "LINK_FAILED", before,
                        "FAILED_UNKNOWN", record.getLinkResult());
            });
        } catch (Exception persistException) {
            log.error("QMS CAPA失败结果保存失败，MES冻结基线仍保留, improvementId={}", id, persistException);
        }
    }

    private QmsCapaExternalCreateCommand toCommand(MesChangePointImprovementEntity record) {
        String evidence = "口径=" + record.getDimension() + "/" + record.getGroupCode()
                + "；时间窗=[" + record.getBaselineStartTime() + "," + record.getBaselineEndTime() + ")"
                + "；事件=" + record.getBaselineEventCount()
                + "；暂停失败=" + record.getBaselinePauseFailureCount()
                + "；受影响/返工/报废=" + record.getBaselineAffectedWipQty() + "/"
                + record.getBaselineReworkWipQty() + "/" + record.getBaselineScrapWipQty()
                + "；损失金额=" + record.getBaselineLossAmount()
                + "；平均闭环分钟=" + record.getBaselineAverageCloseMinutes()
                + "；SHA-256=" + record.getBaselineFingerprint();
        return new QmsCapaExternalCreateCommand().setRequestId(record.getQmsRequestId())
                .setSourceType("MES_CHANGE_POINT_PARETO").setSourceId(record.getId())
                .setSourceNo(record.getImprovementNo()).setSourceEvidence(evidence)
                .setTitle(record.getTitle()).setProblemStatement(record.getProblemStatement())
                .setImpactScope(record.getImpactScope()).setTriggerReason(record.getTriggerReason())
                .setSeverity(record.getSeverity()).setOccurrence(record.getOccurrence())
                .setDetection(record.getDetection()).setTeamMembers(record.getTeamMembers())
                .setCoordinatorCode(record.getCoordinatorCode()).setCoordinatorName(record.getCoordinatorName())
                .setDueTime(record.getDueTime()).setFactoryCode(record.getFactoryCode());
    }

    private void validateLink(MesChangePointImprovementEntity record, QmsCapaLinkDTO link) {
        if (link.getCapaId() == null || StrUtil.isBlank(link.getCapaNo())
                || !Objects.equals("MES_CHANGE_POINT_PARETO", link.getSourceType())
                || !Objects.equals(record.getId(), link.getSourceId())
                || !Objects.equals(record.getImprovementNo(), link.getSourceNo())
                || !Objects.equals(record.getFactoryCode(), link.getFactoryCode())) {
            throw new CheckedException("QMS返回的CAPA来源身份或工厂与MES冻结基线不一致");
        }
    }

    private MesChangePointImprovementEntity lock(Long id) {
        MesChangePointImprovementEntity record = mapper.selectOne(
                new LambdaQueryWrapper<MesChangePointImprovementEntity>()
                        .eq(MesChangePointImprovementEntity::getId, id).last("FOR UPDATE"));
        if (record == null) throw new CheckedException("变化点改善来源单不存在");
        return record;
    }

    private TransactionTemplate requiresNew() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }

    private String safeMessage(Exception exception) {
        return StrUtil.subPre(StrUtil.blankToDefault(exception.getMessage(), exception.getClass().getSimpleName()), 350);
    }
}
