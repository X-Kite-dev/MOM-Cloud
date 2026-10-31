package com.kitex.mom.mes.service.impl;

import static com.kitex.mom.mes.service.impl.MesReworkCompletionCodec.*;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.MesSourceQuantityKind;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.MesApsSourceQuantityService;
import com.kitex.mom.qms.api.feign.RemoteQmsReworkService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@RequiredArgsConstructor
public class MesReworkCompletionOutbox {
    private final MesReworkCompletionOutboxMapper originals;
    private final MesTaskMapper tasks;
    private final MesProduceTaskMapper operations;
    private final MesApsSourceQuantityService sourceQuantity;
    private final RemoteQmsReworkService qms;

    @Transactional(rollbackFor = Exception.class)
    public void prepare(Long taskId, String code, BigDecimal quantity, boolean retry) {
        ready();
        var task = task(taskId);
        var stored = original(taskId);
        if (stored != null) {
            var s = read(stored, task);
            var op = operation(task, s.command().mesProduceTaskCode());
            binding(task, op, s.command());
            require(
                    retry
                            || Objects.equals(code, s.command().mesProduceTaskCode())
                                    && integer(quantity) == s.command().completedQuantity(),
                    "本次完工与已冻结的最终工序或数量不一致");
            return;
        }
        require(
                !"FAILED".equals(task.getReworkCallbackStatus())
                        && !"SUCCEEDED".equals(task.getReworkCallbackStatus())
                        && Objects.requireNonNullElse(task.getReworkCallbackCount(), 0) == 0
                        && task.getReinspectionTaskId() == null,
                "旧返工已有回传尝试或复检但未留存发送原件，不能重建，请核对原记录");
        require(
                task.getReworkCallbackStatus() == null
                        || "PENDING".equals(task.getReworkCallbackStatus()),
                "返工回传状态不可识别");
        MesProduceTaskEntity op;
        if (retry) {
            op =
                    operations.selectOne(
                            new LambdaQueryWrapper<MesProduceTaskEntity>()
                                    .eq(MesProduceTaskEntity::getTenantId, task.getTenantId())
                                    .eq(MesProduceTaskEntity::getFactoryCode, task.getFactoryCode())
                                    .eq(MesProduceTaskEntity::getTaskCode, task.getCode())
                                    .eq(MesProduceTaskEntity::getLastRoute, true));
            quantity = task.getCompleteNum();
        } else op = operation(task, code);
        require(op != null, "返工工单缺少唯一最终实际工序");
        var c = command(task, op, quantity);
        binding(task, op, c);
        var source = source(task);
        if (source.getPlanNo() != null || source.getApsBatchNo() != null)
            sourceQuantity.recordDeferred(
                    source,
                    "REWORK-COMPLETED:" + task.getReworkRequestId(),
                    MesSourceQuantityKind.REWORK_COMPLETED,
                    BigDecimal.valueOf(c.completedQuantity()),
                    task.getCode(),
                    "返工工单【" + task.getCode() + "】完工，数量【" + c.completedQuantity() + "】");
        var user = SecurityUtils.getUser();
        require(
                user == null
                        || Objects.equals(user.getTenantId(), task.getTenantId())
                                && primary(user.getId()),
                "返工发送审计人员与实际租户不一致");
        Long actor = user == null ? null : user.getId();
        Long id = IdUtil.getSnowflakeNextId();
        long now = System.currentTimeMillis();
        String raw = encode(snapshot(id, task, source, op, c, actor, now));
        require(raw.getBytes(StandardCharsets.UTF_8).length <= 65536, "返工完工发送原件超过64KiB");
        var row =
                new MesReworkCompletionOutboxEntity()
                        .setId(id)
                        .setTenantId(task.getTenantId())
                        .setFactoryCode(task.getFactoryCode())
                        .setTaskId(task.getId())
                        .setRequestId(c.requestId())
                        .setPayload(raw)
                        .setPayloadFingerprint(hash(raw))
                        .setCreatedBy(actor)
                        .setCreatedAt(new Date(now));
        require(originals.insert(row) == 1, "返工完工发送原件未保存，禁止投递");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Long deliver(Long taskId) {
        ready();
        var task = task(taskId);
        var row = original(taskId);
        require(row != null, "返工完工发送原件缺失，不能确认或重建");
        var snapshot = read(row, task);
        var c = snapshot.command();
        binding(task, operation(task, c.mesProduceTaskCode()), c);
        require(
                task.getReinspectionTaskId() == null || primary(task.getReinspectionTaskId()),
                "原复检关联编号无效");
        int old = Objects.requireNonNullElse(task.getReworkCallbackCount(), 0);
        require(old >= 0, "返工回传次数无效");
        if ("SUCCEEDED".equals(task.getReworkCallbackStatus())) {
            require(primary(task.getReinspectionTaskId()), "已确认返工回传缺少有效复检");
            return task.getReinspectionTaskId();
        }
        require(
                task.getReworkCallbackStatus() == null
                        || Set.of("PENDING", "FAILED").contains(task.getReworkCallbackStatus()),
                "返工回传状态不可识别");
        int count = Math.addExact(old, 1);
        R<Long> response;
        try {
            response = qms.mesComplete(dto(c));
        } catch (RuntimeException failure) {
            progress(task, count, null, "QMS回传未确认：" + failure.getClass().getSimpleName());
            return null;
        }
        if (response == null || !response.isOk() || !primary(response.getData())) {
            progress(
                    task,
                    count,
                    null,
                    response == null
                            ? "QMS未返回复检回执"
                            : StrUtil.blankToDefault(response.getMsg(), "QMS未返回有效复检编号"));
            return null;
        }
        require(
                task.getReinspectionTaskId() == null
                        || Objects.equals(task.getReinspectionTaskId(), response.getData()),
                "QMS回执与已保存复检关联冲突，不能覆盖原单");
        progress(task, count, response.getData(), "已创建返工复检任务");
        return response.getData();
    }

    private void progress(MesTaskEntity task, int count, Long child, String message) {
        var update =
                new LambdaUpdateWrapper<MesTaskEntity>()
                        .eq(MesTaskEntity::getId, task.getId())
                        .eq(MesTaskEntity::getTenantId, task.getTenantId())
                        .eq(MesTaskEntity::getFactoryCode, task.getFactoryCode())
                        .set(
                                MesTaskEntity::getReworkCallbackStatus,
                                child == null ? "FAILED" : "SUCCEEDED")
                        .set(
                                MesTaskEntity::getReworkCallbackMessage,
                                StrUtil.maxLength(message, 1000))
                        .set(MesTaskEntity::getReworkCallbackCount, count);
        if (child != null)
            update.set(MesTaskEntity::getReinspectionTaskId, child)
                    .set(MesTaskEntity::getReinspectionStatus, "WAIT")
                    .set(MesTaskEntity::getReinspectionMessage, "等待QMS返工复检结论");
        require(tasks.update(null, update) == 1, "返工投递进度未保存，发送原件保留，请沿原号核对");
    }

    private Snapshot read(MesReworkCompletionOutboxEntity row, MesTaskEntity task) {
        var raw = row.getPayload();
        require(
                raw != null
                        && raw.getBytes(StandardCharsets.UTF_8).length <= 65536
                        && Objects.equals(hash(raw), row.getPayloadFingerprint()),
                "返工发送原件指纹损坏");
        var s = decode(raw);
        require(s != null && encode(s).equals(raw), "返工发送原件非本版本确定性原文");
        validate(s.command());
        require(
                primary(row.getId())
                        && Objects.equals(row.getTenantId(), task.getTenantId())
                        && Objects.equals(row.getFactoryCode(), task.getFactoryCode())
                        && Objects.equals(row.getTaskId(), task.getId())
                        && Objects.equals(row.getRequestId(), task.getReworkRequestId())
                        && s.createdAt() > 0
                        && row.getCreatedAt() != null
                        && row.getCreatedAt().getTime() == s.createdAt()
                        && Objects.equals(row.getCreatedBy(), s.createdBy())
                        && (s.createdBy() == null || primary(s.createdBy())),
                "返工发送原件索引或审计不一致");
        var op = operation(task, s.command().mesProduceTaskCode());
        require(op != null, "返工发送原件对应的实际工序不存在");
        require(
                snapshot(
                                row.getId(),
                                task,
                                source(task),
                                op,
                                s.command(),
                                s.createdBy(),
                                s.createdAt())
                        .equals(s),
                "返工发送原件与实际来源或最终工序不一致");
        return s;
    }

    private MesTaskEntity task(Long id) {
        require(TransactionSynchronizationManager.isActualTransactionActive(), "返工原件必须在实际事务内处理");
        require(primary(id), "返工工单编号无效");
        var t =
                tasks.selectOne(
                        new LambdaQueryWrapper<MesTaskEntity>()
                                .eq(MesTaskEntity::getId, id)
                                .eq(MesTaskEntity::getTenantId, TenantContextHolder.getTenantId())
                                .eq(
                                        MesTaskEntity::getFactoryCode,
                                        FactoryContextHolder.getFactoryCode())
                                .last("FOR UPDATE"));
        require(
                t != null
                        && "REWORK".equals(t.getResourceType())
                        && "FINISH".equals(t.getStatus())
                        && code(t.getCode())
                        && code(t.getReworkRequestId())
                        && code(t.getSourceTaskCode())
                        && primary(t.getSourceInspectionTaskId())
                        && primary(t.getSourceHandleRecordId())
                        && Set.of("PRODUCT", "PROCEDURE")
                                .contains(Objects.toString(t.getSourceInspectionType(), "")),
                "实际组织的返工完工来源不完整");
        return t;
    }

    private MesTaskEntity source(MesTaskEntity task) {
        var s =
                tasks.selectOne(
                        new LambdaQueryWrapper<MesTaskEntity>()
                                .eq(MesTaskEntity::getTenantId, task.getTenantId())
                                .eq(MesTaskEntity::getFactoryCode, task.getFactoryCode())
                                .eq(MesTaskEntity::getCode, task.getSourceTaskCode()));
        require(
                s != null && primary(s.getId()) && !Objects.equals(s.getId(), task.getId()),
                "返工原生产工单缺失或已删除");
        return s;
    }

    private MesProduceTaskEntity operation(MesTaskEntity task, String code) {
        require(MesReworkCompletionCodec.code(code), "返工最终工序编码无效");
        return operations.selectOne(
                new LambdaQueryWrapper<MesProduceTaskEntity>()
                        .eq(MesProduceTaskEntity::getTenantId, task.getTenantId())
                        .eq(MesProduceTaskEntity::getFactoryCode, task.getFactoryCode())
                        .eq(MesProduceTaskEntity::getTaskCode, task.getCode())
                        .eq(MesProduceTaskEntity::getCode, code));
    }

    private void binding(MesTaskEntity task, MesProduceTaskEntity op, Command c) {
        validate(c);
        require(
                op != null
                        && primary(op.getId())
                        && Boolean.TRUE.equals(op.getLastRoute())
                        && "FINISH".equals(op.getStatus())
                        && Objects.equals(c.requestId(), task.getReworkRequestId())
                        && Objects.equals(c.mesTaskCode(), task.getCode())
                        && Objects.equals(c.mesProduceTaskCode(), op.getCode())
                        && Objects.equals(c.processCode(), op.getRouteCode())
                        && Objects.equals(c.processCode(), task.getReworkProcessCode())
                        && integer(task.getCompleteNum()) == c.completedQuantity()
                        && integer(op.getCompleteNum()) == c.completedQuantity(),
                "返工回传与实际最终工序、来源或累计数量不符");
    }

    private MesReworkCompletionOutboxEntity original(Long taskId) {
        var rows =
                originals.original(
                        TenantContextHolder.getTenantId(),
                        FactoryContextHolder.getFactoryCode(),
                        taskId);
        require(rows != null && rows.size() <= 1, "同一返工工单发送原件不唯一");
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void ready() {
        MesQualityGateSourceEvidence.scope();
        try {
            require(Integer.valueOf(1).equals(originals.schemaVersion()), "返工完工发送原件结构未就绪，请完成数据库升级");
        } catch (org.springframework.dao.DataAccessException e) {
            throw new CheckedException("返工完工发送原件结构未就绪，请完成数据库升级", e);
        }
    }
}
