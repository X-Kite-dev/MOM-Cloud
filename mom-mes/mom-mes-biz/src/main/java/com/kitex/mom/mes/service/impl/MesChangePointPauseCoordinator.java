package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.entity.MesChangePointHandleEntity;
import com.kitex.mom.mes.entity.MesChangePointOperationEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.mapper.MesChangePointHandleMapper;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.service.MesChangePointHandleService;
import com.kitex.mom.mes.service.MesChangePointOperationService;
import com.kitex.mom.mes.service.MesTaskArrangeService;
import com.kitex.mom.tpm.dto.TpmDevicePauseReceipt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 变化点与生产暂停的可靠协调器。
 *
 * <p>变化点事件先独立提交，再尝试暂停生产。TPM不可用或回执结果不确定时不撤销已经生效的
 * 逻辑生产门，而是保存尝试号和失败结果供现场核对及幂等重试。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MesChangePointPauseCoordinator {

    private static final Set<String> TERMINAL = Set.of("1", "9");

    private final MesChangePointHandleMapper handleMapper;
    private final MesTaskArrangeMapper arrangeMapper;
    private final MesChangePointHandleService handleService;
    private final MesChangePointOperationService operationService;
    private final MesTaskArrangeService arrangeService;
    private final PlatformTransactionManager transactionManager;
    private final MesExecutionTaskLock executionTaskLock;

    /**
     * 在控制器完成变化点主事务后尝试暂停，失败会转为可见业务结果而不是回滚事件。
     */
    public void attempt(Long id) {
        String attemptId = UUID.randomUUID().toString();
        AtomicReference<TpmDevicePauseReceipt> receiptRef = new AtomicReference<>();
        TransactionTemplate transaction = requiresNew();
        try {
            transaction.executeWithoutResult(status -> attemptInTransaction(id, attemptId, receiptRef));
        } catch (Exception exception) {
            log.error("变化点已受理但生产暂停联动失败, eventId={}, attemptId={}", id, attemptId, exception);
            persistFailure(id, attemptId, receiptRef.get(), exception, transaction);
        }
    }

    /**
     * 人工重试仍沿用服务端新生成的尝试号，客户端不能伪造远端幂等键。
     */
    public void retry(Long id) {
        MesChangePointHandleEntity record = handleService.getDetail(id);
        if (!Boolean.TRUE.equals(record.getAutoPauseRequired()) || TERMINAL.contains(record.getStatus())) {
            throw new CheckedException("当前变化点不需要联动暂停或已经结束");
        }
        if (Boolean.TRUE.equals(record.getProductionPaused())) {
            throw new CheckedException("关联派工已经确认暂停，无需重复执行");
        }
        if (record.getPauseOutcome() != null && !Set.of("FAILED_UNKNOWN", "MDM_CONFIRMED_MES_FAILED")
                .contains(record.getPauseOutcome())) {
            throw new CheckedException("关联派工当前不在需要重试暂停的失败状态");
        }
        attempt(id);
    }

    private void attemptInTransaction(Long id, String attemptId,
                                      AtomicReference<TpmDevicePauseReceipt> receiptRef) {
        // 原事件无锁定位；必须在派工/事件行锁前取得共同工单锁，不能由旧事件字段指定另一个工单。
        MesChangePointHandleEntity observed = handleMapper.selectById(id);
        if (observed == null || !Boolean.TRUE.equals(observed.getAutoPauseRequired())
                || TERMINAL.contains(observed.getStatus())) return;
        if (StrUtil.isBlank(observed.getArrangeCode())) {
            throw new CheckedException("自动暂停配置必须关联唯一派工单");
        }
        MesTaskArrangeEntity beforeLock = executionTaskLock.arrange(observed.getArrangeCode());
        if (!java.util.Objects.equals(observed.getTenantId(), beforeLock.getTenantId())
                || !java.util.Objects.equals(observed.getFactoryCode(), beforeLock.getFactoryCode())) {
            throw new CheckedException("变化点事件与实际派工组织不一致");
        }
        MesTaskArrangeEntity arrange = arrangeMapper.selectOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getId, beforeLock.getId())
                .eq(MesTaskArrangeEntity::getTenantId, beforeLock.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, beforeLock.getFactoryCode()).last("FOR UPDATE"));
        executionTaskLock.assertArrange(beforeLock, arrange);
        MesChangePointHandleEntity event = handleMapper.selectOne(
                new LambdaQueryWrapper<MesChangePointHandleEntity>()
                        .eq(MesChangePointHandleEntity::getId, id)
                        .eq(MesChangePointHandleEntity::getTenantId, beforeLock.getTenantId())
                        .eq(MesChangePointHandleEntity::getFactoryCode, beforeLock.getFactoryCode()).last("FOR UPDATE"));
        if (event == null || !Boolean.TRUE.equals(event.getAutoPauseRequired()) || TERMINAL.contains(event.getStatus())) {
            return;
        }
        if (StrUtil.isBlank(event.getArrangeCode())) {
            throw new CheckedException("自动暂停配置必须关联唯一派工单");
        }
        if (!java.util.Objects.equals(event.getArrangeCode(), arrange.getCode())) {
            throw new CheckedException("变化点派工关联已变化，请重新核对原事件");
        }

        String before = arrange.getStatus();
        String outcome;
        String result;
        boolean paused;
        boolean pausedByEvent = Boolean.TRUE.equals(event.getPausedByChangePoint());
        if ("PRODUCT".equals(before)) {
            TpmDevicePauseReceipt receipt = arrangeService.pauseByChangePoint(arrange.getCode(), attemptId);
            receiptRef.set(receipt);
            paused = true;
            pausedByEvent = true;
            outcome = receipt == null ? "LOCAL_ONLY" : "MDM_CONFIRMED";
            result = receipt == null
                    ? "派工已暂停；共享设备或未绑定独占设备，本次只转换MES执行状态"
                    : "派工已暂停，并取得匹配的TPM/MDM设备状态事务回执";
        } else if ("PAUSE".equals(before)) {
            paused = true;
            outcome = "ALREADY_PAUSED";
            result = pausedByEvent ? "本变化点此前已经暂停关联派工" : "关联派工触发前已经暂停，本事件未重复操作设备";
        } else {
            paused = false;
            outcome = "NOT_RUNNING";
            result = "关联派工当前状态=" + before + "，无需从生产中切换为暂停；变化点逻辑门持续有效";
        }
        event.setProductionPaused(paused).setPausedByChangePoint(pausedByEvent)
                .setPauseAttemptId(attemptId).setPauseOutcome(outcome)
                .setProductionPauseResult(result)
                .setProductionPauseTime(paused ? LocalDateTime.now() : null);
        if (handleMapper.updateById(event) != 1) throw new IllegalStateException("变化点暂停结果保存失败");
        saveOperation(event, attemptId, outcome, before, result, receiptRef.get());
    }

    private void persistFailure(Long id, String attemptId, TpmDevicePauseReceipt receipt, Exception exception,
                                TransactionTemplate transaction) {
        try {
            transaction.executeWithoutResult(status -> {
                MesChangePointHandleEntity event = handleMapper.selectOne(
                        new LambdaQueryWrapper<MesChangePointHandleEntity>()
                                .eq(MesChangePointHandleEntity::getId, id).last("FOR UPDATE"));
                if (event == null || TERMINAL.contains(event.getStatus())) return;
                String outcome = receipt == null ? "FAILED_UNKNOWN" : "MDM_CONFIRMED_MES_FAILED";
                String result = "暂停联动失败或结果不确定，变化点逻辑门仍有效；必须按尝试号现场核对后重试："
                        + StrUtil.subPre(StrUtil.blankToDefault(exception.getMessage(), exception.getClass().getSimpleName()), 300);
                event.setProductionPaused(false).setPauseAttemptId(attemptId).setPauseOutcome(outcome)
                        .setProductionPauseResult(result).setProductionPauseTime(null);
                if (handleMapper.updateById(event) != 1) throw new IllegalStateException("暂停失败结果保存失败");
                saveOperation(event, attemptId, outcome, null, result, receipt);
            });
        } catch (Exception persistException) {
            log.error("变化点暂停失败结果保存失败，既有逻辑门未撤销, eventId={}", id, persistException);
        }
    }

    private void saveOperation(MesChangePointHandleEntity event, String attemptId, String outcome,
                               String arrangeStatusBefore, String result, TpmDevicePauseReceipt receipt) {
        LinkedHashMap<String, Object> facts = new LinkedHashMap<>();
        facts.put("version", "CHANGE_POINT_PAUSE_RECEIPT_V1");
        facts.put("attemptId", attemptId);
        facts.put("arrangeCode", event.getArrangeCode());
        facts.put("taskCode", event.getTaskCode());
        facts.put("produceTaskCode", event.getProduceTaskCode());
        facts.put("arrangeStatusBefore", arrangeStatusBefore);
        facts.put("outcome", outcome);
        facts.put("result", result);
        facts.put("deviceReceipt", receipt);
        MesChangePointOperationEntity operation = new MesChangePointOperationEntity()
                .setRequestId(attemptId).setHandleId(event.getId()).setEventCode(event.getEventCode())
                .setOperationType("PAUSE_LINK").setBeforeStatus(event.getStatus()).setAfterStatus(event.getStatus())
                .setContent(StrUtil.subPre(JSONUtil.toJsonStr(facts), 2000))
                .setOperatorCode("SYSTEM").setOperatorName("系统联动")
                .setOperationTime(LocalDateTime.now()).setFactoryCode(event.getFactoryCode());
        operation.setTenantId(event.getTenantId());
        if (!operationService.save(operation)) throw new IllegalStateException("变化点暂停证据保存失败");
    }

    private TransactionTemplate requiresNew() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }
}
