package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.entity.MesAndonOperationEntity;
import com.kitex.mom.mes.entity.MesAndonRecordEntity;
import com.kitex.mom.mes.mapper.MesAndonOperationMapper;
import com.kitex.mom.mes.mapper.MesAndonRecordMapper;
import com.kitex.mom.mes.service.MesTaskArrangeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 安灯及逻辑阻断先提交；TPM不可用不能让已受理的异常跟随暂停事务一起丢失。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MesAndonPauseCoordinator {
    private final MesAndonRecordMapper events;
    private final MesAndonOperationMapper operations;
    private final MesAndonProductionReleaseService releaseService;
    private final MesTaskArrangeService arrangeService;
    private final PlatformTransactionManager transactionManager;

    @PreAuthorize("@pms.hasPermission('andonRecord:show')")
    public Object history(Long id, long page) {
        if (page < 1 || page > 100000) throw new com.kitex.common.core.exception.CheckedException("页码无效");
        var event = releaseService.readScopedEvent(id);
        return operations.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, 30),
                new LambdaQueryWrapper<MesAndonOperationEntity>().eq(MesAndonOperationEntity::getRecordId, id)
                        .eq(MesAndonOperationEntity::getTenantId, event.getTenantId()).eq(MesAndonOperationEntity::getFactoryCode, event.getFactoryCode())
                        .eq(MesAndonOperationEntity::getOperationType, "PAUSE_LINK").orderByDesc(MesAndonOperationEntity::getId));
    }

    @PreAuthorize("@pms.hasPermission('andonRecord:resumeRequest')")
    public void retry(Long id) {
        String factory = releaseService.authorizeFactory("write");
        var row = events.selectOne(new LambdaQueryWrapper<MesAndonRecordEntity>().eq(MesAndonRecordEntity::getId, id)
                .eq(MesAndonRecordEntity::getFactoryCode, factory));
        if (row == null || !Boolean.TRUE.equals(row.getProductionReleaseRequired())) {
            throw new com.kitex.common.core.exception.CheckedException("当前工厂没有可联动暂停的受控安灯");
        }
        attempt(id);
    }

    public void attempt(Long id) {
        String attemptId = java.util.UUID.randomUUID().toString();
        var receipt = new java.util.concurrent.atomic.AtomicReference<com.kitex.mom.tpm.dto.TpmDevicePauseReceipt>();
        var context = new java.util.concurrent.atomic.AtomicReference<String>();
        var tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            tx.executeWithoutResult(status -> {
                var first = events.selectById(id);
                if (first == null || !Boolean.TRUE.equals(first.getProductionReleaseRequired())) return;
                var arrange = releaseService.lockLinkedArrange(first.getArrangeCode(), first.getFactoryCode());
                var event = events.selectOne(new LambdaQueryWrapper<MesAndonRecordEntity>()
                        .eq(MesAndonRecordEntity::getId, id).last("FOR UPDATE"));
                if (event == null || "closed".equals(event.getState()) || "cancel".equals(event.getState())) return;
                var facts = new java.util.LinkedHashMap<String, Object>();
                facts.put("version", "ANDON_PAUSE_RECEIPT_V1");
                facts.put("requestId", attemptId);
                facts.put("arrangeId", arrange.getId().toString());
                facts.put("arrangeCode", arrange.getCode());
                facts.put("taskCode", arrange.getTaskCode());
                facts.put("produceTaskCode", arrange.getProduceTaskCode());
                facts.put("deviceCode", arrange.getPlannedDeviceCode());
                facts.put("worksiteCode", arrange.getActualWorksiteCode());
                facts.put("arrangeStatusBefore", arrange.getStatus());
                context.set(cn.hutool.json.JSONUtil.toJsonStr(facts));
                if ("PRODUCT".equals(arrange.getStatus())) {
                    receipt.set(arrangeService.pauseByAndon(arrange.getCode(), attemptId));
                    event.setProductionPaused(true);
                    event.setProductionPauseResult("派工暂停已确认；独占设备由TPM取得MDM状态事务回执，保留维修/保养和可用性；共享设备未发暂停。业务状态不代表硬件停机，复产须独立批准并重新开工");
                } else {
                    event.setProductionPaused("PAUSE".equals(arrange.getStatus()));
                    event.setProductionPauseResult("派工当前状态=" + arrange.getStatus() + "；未新增设备停机动作，生产逻辑阻断持续有效");
                }
                if (events.updateById(event) != 1) throw new IllegalStateException("暂停结果保存失败");
                audit(event, attemptId, receipt.get() == null ? "LOCAL_ONLY" : "MDM_CONFIRMED", context.get(), receipt.get());
            });
        } catch (Exception ex) {
            log.error("安灯已受理但暂停联动失败，recordId={}", id, ex);
            try {
                tx.executeWithoutResult(status -> {
                    var event = events.selectOne(new LambdaQueryWrapper<MesAndonRecordEntity>()
                            .eq(MesAndonRecordEntity::getId, id).last("FOR UPDATE"));
                    if (event == null || Boolean.TRUE.equals(event.getProductionPaused())) return;
                    event.setProductionPauseResult("暂停联动失败/结果不确定，逻辑阻断仍有效；须现场确认并重试："
                            + StrUtil.subPre(StrUtil.blankToDefault(ex.getMessage(), ex.getClass().getSimpleName()), 350));
                    events.updateById(event);
                    // 远端已确认但MES写入失败，与连远端结果也不明分开；绝不自动恢复设备运行。
                    audit(event, attemptId, receipt.get() == null ? "FAILED_UNKNOWN" : "MDM_CONFIRMED_MES_FAILED", context.get(), receipt.get());
                });
            } catch (Exception persistFailure) {
                log.error("安灯暂停失败结果保存失败，既有持久化生产阻断未撤销，recordId={}", id, persistFailure);
            }
        }
    }

    private void audit(MesAndonRecordEntity event, String requestId, String outcome, String context,
                       com.kitex.mom.tpm.dto.TpmDevicePauseReceipt receipt) {
        var actor = SecurityUtils.getUser();
        var record = new MesAndonOperationEntity().setRecordId(event.getId()).setRecordCode(event.getCode())
                .setRequestId(requestId).setOperationType("PAUSE_LINK").setBeforeState(event.getState()).setAfterState(event.getState())
                .setContent(event.getProductionPauseResult()).setOperationTime(java.time.LocalDateTime.now())
                .setOperatorCode(actor == null ? null : String.valueOf(actor.getId())).setOperatorName(actor == null ? null : actor.getName())
                .setFactoryCode(event.getFactoryCode()).setPauseOutcome(outcome).setPauseContextJson(context)
                .setDevicePauseReceiptJson(receipt == null ? null : cn.hutool.json.JSONUtil.toJsonStr(receipt));
        record.setTenantId(event.getTenantId());
        if (operations.insert(record) != 1) throw new IllegalStateException("暂停尝试证据保存失败");
    }
}
