package com.kitex.mom.qms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.qms.dto.QmsPlanEventDTO;
import com.kitex.mom.qms.entity.QmsPlanEventOperationEntity;
import com.kitex.mom.qms.entity.QmsPlanEventOutboxEntity;
import com.kitex.mom.qms.entity.QmsPlanEventTargetEntity;
import com.kitex.mom.qms.mapper.QmsPlanEventOperationMapper;
import com.kitex.mom.qms.mapper.QmsPlanEventOutboxMapper;
import com.kitex.mom.qms.mapper.QmsPlanEventTargetMapper;
import com.kitex.mom.qms.service.QmsPlanEventOutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * QMS 质量事实 Outbox 写入与管理实现。
 *
 * <p>本类不执行远端投递，只在当前业务事务内固化事件和来源指纹。远端 MES/PLAN 调用由
 * {@link QmsPlanEventOutboxProcessor} 调度 {@link QmsPlanEventOutboxWorker} 完成。</p>
 */
@Service
@RequiredArgsConstructor
public class QmsPlanEventOutboxServiceImpl implements QmsPlanEventOutboxService {
    private final QmsPlanEventOutboxMapper outboxMapper;
    private final QmsPlanEventTargetMapper targetMapper;
    private final QmsPlanEventOperationMapper operationMapper;
    private final QmsQualityDispositionSourceService sourceService;

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QmsPlanEventOutboxEntity enqueueIsolation(String inspectionType, Long inspectionTaskId) {
        return enqueue(sourceService.loadIsolation(inspectionType, inspectionTaskId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QmsPlanEventOutboxEntity enqueueDisposition(String inspectionType, Long inspectionTaskId,
                                                       Long handleRecordId) {
        return enqueue(sourceService.load(inspectionType, inspectionTaskId, handleRecordId));
    }

    private QmsPlanEventOutboxEntity enqueue(QmsQualityDispositionSourceService.Source source) {
        requireFactory(source.factoryCode());
        boolean isolation = "ISOLATE".equals(source.dispositionKind());
        Long sourceId = isolation ? source.inspectionTaskId() : source.handleRecordId();
        String sourceType = "QMS_" + source.inspectionType() + (isolation ? "_ISOLATION" : "_DISPOSITION");
        // 保留已上线报废事件的业务号和MES幂等号，升级后重扫不得重复记账。
        String eventId = "SMASH".equals(source.dispositionKind())
                ? "QMS-DISPOSITION:" + source.inspectionType() + ':' + sourceId + ":V1"
                : "QMS-" + source.dispositionKind() + ':' + source.inspectionType() + ':' + sourceId + ":V1";
        outboxMapper.lockKey(eventId);
        QmsPlanEventOutboxEntity existing = outboxMapper.selectOne(
                new LambdaQueryWrapper<QmsPlanEventOutboxEntity>()
                        .eq(QmsPlanEventOutboxEntity::getEventId, eventId));
        String payload = sourceService.snapshot(source);
        String fingerprint = sourceService.hash(payload);
        if (existing != null) {
            require(Objects.equals(existing.getSourceFingerprint(), fingerprint),
                    "同一QMS处置事件ID的权威事实已变化，禁止覆盖");
            return existing;
        }
        String mesRequestId = switch (source.dispositionKind()) {
            case "REWORK" -> "REWORK-CREATED:QMS-RW-" + source.inspectionType() + '-' + source.handleRecordId();
            case "SMASH" -> "QMS-SCRAP:" + source.inspectionType() + ':' + sourceId;
            default -> "QMS-" + source.dispositionKind() + ':' + source.inspectionType() + ':' + sourceId;
        };
        LocalDateTime now = LocalDateTime.now();
        QmsPlanEventOutboxEntity outbox = new QmsPlanEventOutboxEntity()
                .setEventId(eventId).setEventType("QMS_QUALITY_CHANGED").setEventVersion(1)
                .setSourceType(sourceType).setSourceId(sourceId).setSourceNo(source.sourceRequestId())
                .setInspectionType(source.inspectionType()).setInspectionTaskId(source.inspectionTaskId())
                .setHandleRecordId(source.handleRecordId()).setMesTaskCode(source.mesTaskCode())
                .setMesProduceTaskCode(source.mesProduceTaskCode()).setDispositionKind(source.dispositionKind())
                .setQuantity(source.quantity()).setReason(source.reason()).setOccurredTime(source.occurredTime())
                .setSourcePayloadJson(payload).setSourceFingerprint(fingerprint).setMesRequestId(mesRequestId)
                .setMesSyncStatus("PENDING").setStatus("PENDING").setTargetCount(0).setDeliveredTargetCount(0)
                .setAttemptCount(0).setMaxAttempts(8).setNextAttemptTime(now).setFactoryCode(source.factoryCode());
        require(outboxMapper.insert(outbox) == 1, "QMS质量处置Outbox保存失败");
        operation(outbox, null, "CREATE:" + eventId, "OUTBOX_CREATED", null, "PENDING", 0,
                "质量" + source.dispositionKind() + "事实与QMS业务事务同箱保存，等待幂等同步MES并逐来源计划投递PLAN");
        return outbox;
    }

    @Override
    public Page<QmsPlanEventOutboxEntity> page(SearchPage page, QmsPlanEventDTO.Query query) {
        QmsPlanEventDTO.Query q = query == null ? new QmsPlanEventDTO.Query() : query;
        return outboxMapper.selectPage(page.toMpPage(), new LambdaQueryWrapper<QmsPlanEventOutboxEntity>()
                .like(StrUtil.isNotBlank(q.getEventId()), QmsPlanEventOutboxEntity::getEventId, q.getEventId())
                .eq(StrUtil.isNotBlank(q.getInspectionType()), QmsPlanEventOutboxEntity::getInspectionType, q.getInspectionType())
                .like(StrUtil.isNotBlank(q.getSourceNo()), QmsPlanEventOutboxEntity::getSourceNo, q.getSourceNo())
                .like(StrUtil.isNotBlank(q.getMesTaskCode()), QmsPlanEventOutboxEntity::getMesTaskCode, q.getMesTaskCode())
                .eq(StrUtil.isNotBlank(q.getDispositionKind()), QmsPlanEventOutboxEntity::getDispositionKind, q.getDispositionKind())
                .eq(StrUtil.isNotBlank(q.getStatus()), QmsPlanEventOutboxEntity::getStatus, q.getStatus())
                .orderByAsc(QmsPlanEventOutboxEntity::getStatus)
                .orderByDesc(QmsPlanEventOutboxEntity::getOccurredTime));
    }

    @Override
    public QmsPlanEventDTO.Detail detail(Long outboxId) {
        QmsPlanEventOutboxEntity outbox = requireOutbox(outboxId, false);
        QmsPlanEventDTO.Detail detail = new QmsPlanEventDTO.Detail();
        BeanUtils.copyProperties(outbox, detail);
        detail.setTargets(targetMapper.selectList(new LambdaQueryWrapper<QmsPlanEventTargetEntity>()
                .eq(QmsPlanEventTargetEntity::getOutboxId, outboxId)
                .orderByAsc(QmsPlanEventTargetEntity::getIssueNo)
                .orderByAsc(QmsPlanEventTargetEntity::getId)));
        detail.setOperations(operationMapper.selectList(new LambdaQueryWrapper<QmsPlanEventOperationEntity>()
                .eq(QmsPlanEventOperationEntity::getOutboxId, outboxId)
                .orderByAsc(QmsPlanEventOperationEntity::getOperationTime)
                .orderByAsc(QmsPlanEventOperationEntity::getId)));
        return detail;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean retry(QmsPlanEventDTO.ManualActionCommand command) {
        require(command != null && command.getOutboxId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getReason()), "人工重试参数不完整");
        String requestId = command.getRequestId().trim();
        String reason = command.getReason().trim();
        require(requestId.length() <= 128, "人工重试幂等号不能超过128个字符");
        require(reason.length() <= 500, "人工重试原因不能超过500个字符");
        QmsPlanEventOutboxEntity outbox = requireOutbox(command.getOutboxId(), true);
        QmsPlanEventOperationEntity duplicate = operationMapper.selectOne(
                new LambdaQueryWrapper<QmsPlanEventOperationEntity>()
                        .eq(QmsPlanEventOperationEntity::getRequestId, requestId));
        if (duplicate != null) {
            require(Objects.equals(duplicate.getOutboxId(), outbox.getId())
                            && "MANUAL_RETRY".equals(duplicate.getOperationType())
                            && Objects.equals(duplicate.getContent(), reason),
                    "人工操作幂等号已被不同事件、动作或内容使用");
            return true;
        }
        require(List.of("RETRY_WAIT", "WAITING_DATA", "DEAD_LETTER").contains(outbox.getStatus()),
                "仅退避、待补数据或死信质量事件允许人工重试");
        QmsQualityDispositionSourceService.Source source = "ISOLATE".equals(outbox.getDispositionKind())
                ? sourceService.loadIsolation(outbox.getInspectionType(), outbox.getInspectionTaskId())
                : sourceService.load(outbox.getInspectionType(), outbox.getInspectionTaskId(), outbox.getHandleRecordId());
        require(Objects.equals(outbox.getSourceFingerprint(), sourceService.fingerprint(source)),
                "QMS来源处置事实已变化，禁止重放旧事件");
        String before = outbox.getStatus();
        MomUser user = currentUser();
        outbox.setStatus(targetCount(outbox.getId()) > 0 ? "READY" : "PENDING")
                .setAttemptCount(0).setNextAttemptTime(LocalDateTime.now())
                .setLastErrorCode(null).setLastErrorMessage(null)
                .setManualRequestId(requestId).setManualReason(reason)
                .setManualOperatorCode(userCode(user)).setManualOperatorName(userName(user))
                .setManualOperationTime(LocalDateTime.now());
        require(outboxMapper.updateById(outbox) == 1, "QMS质量事件人工重试状态保存失败");
        operation(outbox, null, requestId, "MANUAL_RETRY", before, outbox.getStatus(), 0,
                reason);
        return true;
    }

    private int targetCount(Long outboxId) {
        return Math.toIntExact(targetMapper.selectCount(new LambdaQueryWrapper<QmsPlanEventTargetEntity>()
                .eq(QmsPlanEventTargetEntity::getOutboxId, outboxId)));
    }

    private QmsPlanEventOutboxEntity requireOutbox(Long id, boolean lock) {
        require(id != null, "Outbox ID不能为空");
        QmsPlanEventOutboxEntity outbox = lock ? outboxMapper.lockById(id) : outboxMapper.selectById(id);
        require(outbox != null, "QMS质量事件不存在");
        requireFactory(outbox.getFactoryCode());
        return outbox;
    }

    private void operation(QmsPlanEventOutboxEntity outbox, Long targetId, String requestId, String type,
                           String before, String after, int attempt, String content) {
        MomUser user = currentUser();
        require(operationMapper.insert(new QmsPlanEventOperationEntity().setOutboxId(outbox.getId())
                        .setTargetId(targetId).setRequestId(requestId).setOperationType(type)
                        .setBeforeStatus(before).setAfterStatus(after).setAttemptNo(attempt).setContent(content)
                        .setOperatorCode(userCode(user)).setOperatorName(userName(user))
                        .setOperationTime(LocalDateTime.now()).setFactoryCode(outbox.getFactoryCode())) == 1,
                "QMS质量事件操作履历保存失败");
    }

    private MomUser currentUser() {
        try {
            return SecurityUtils.getUser();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String userCode(MomUser user) {
        return user == null ? "SYSTEM" : user.getUsername();
    }

    private String userName(MomUser user) {
        return user == null ? "系统" : StrUtil.blankToDefault(user.getName(), user.getUsername());
    }

    private void requireFactory(String factoryCode) {
        String current = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isBlank(current) || Objects.equals(current, factoryCode), "请求工厂超出当前数据范围");
    }
}
