package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.service.impl.QmsInspectionEvidenceChecks.require;
import static com.kitex.mom.qms.service.impl.QmsReworkCompletionReceiptCodec.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.qms.dto.QmsReworkCompletionDTO;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.mapper.*;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Original receipt and link commit together; reading never manufactures missing historical input.
 */
@Service
@RequiredArgsConstructor
public class QmsReworkCompletionReceiptService {
    private final QmsReworkCompletionReceiptMapper mapper;
    private final QmsReworkOrderMapper orders;
    private final QmsInspectionTraceMapper tasks;
    private final QmsInspectionHistorySupport access;
    private final PermissionService permissions;

    public record View(
            String availability,
            String inspectionType,
            Long orderId,
            Long childTaskId,
            Long tenantId,
            String factoryCode,
            String payloadFingerprint,
            Snapshot snapshot) {}

    public void requireAbsent(QmsReworkOrderEntity row, QmsReworkCompletionDTO input) {
        access.requireScope();
        ready();
        command(input);
        require(
                row != null && row.getReinspectionTaskId() == null && find(row.getId()) == null,
                "协调单或原始回执已有复检绑定，请核对原记录");
    }

    public void verifyReplay(QmsReworkOrderEntity row, QmsReworkCompletionDTO input) {
        access.requireScope();
        ready();
        var original = find(row.getId());
        require(original != null, "历史返工完工未留存原回执，不能确认原号重放，请核对原关联");
        var snapshot = decode(original, row);
        require(snapshot.command().equals(command(input)), "回传与原始MES完工业务回执不一致，请使用原回执核对");
        binding(row, snapshot.command());
    }

    public void record(QmsReworkOrderEntity supplied, QmsReworkCompletionDTO input) {
        access.requireScope();
        ready();
        require(TransactionSynchronizationManager.isActualTransactionActive(), "完工回执必须加入原协调业务事务");
        var row = order(supplied.getInspectionType(), supplied.getId(), true);
        var command = command(input);
        require(find(row.getId()) == null, "同一协调单已有原始完工回执");
        binding(row, command);
        var user = SecurityUtils.getUser();
        require(
                user == null
                        || (Objects.equals(user.getTenantId(), row.getTenantId())
                                && primary(user.getId())),
                "完工回执接收审计人员与实际租户不一致");
        Long actor = user == null ? null : user.getId();
        long now = System.currentTimeMillis();
        var snapshot = snapshot(row, command, actor, now);
        String payload = write(snapshot);
        require(payload.getBytes(StandardCharsets.UTF_8).length <= 65536, "单份完工回执超过64KiB，请先治理");
        var receipt =
                new QmsReworkCompletionReceiptEntity()
                        .setTenantId(row.getTenantId())
                        .setFactoryCode(row.getFactoryCode())
                        .setInspectionType(row.getInspectionType())
                        .setOrderId(row.getId())
                        .setChildTaskId(row.getReinspectionTaskId())
                        .setRequestId(row.getRequestId())
                        .setPayload(payload)
                        .setPayloadFingerprint(sha(payload))
                        .setReceivedBy(actor)
                        .setReceivedAt(new Date(now));
        require(mapper.insert(receipt) == 1 && primary(receipt.getId()), "原始完工回执保存失败，复检关联未提交");
    }

    @Transactional(
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ,
            propagation = Propagation.REQUIRES_NEW)
    public View view(String type, Long orderId) {
        type(type);
        if (!permissions.hasPermission(
                ("PRODUCT".equals(type) ? "productTask" : "procedureTask") + ":show"))
            throw new AccessDeniedException("没有查看该类检验回执的权限");
        access.requireRead();
        ready();
        var row = order(type, orderId, false);
        var receipt = find(orderId);
        if (receipt == null) {
            binding(row, null);
            return new View(
                    "LEGACY_UNAVAILABLE",
                    type,
                    orderId,
                    row.getReinspectionTaskId(),
                    row.getTenantId(),
                    row.getFactoryCode(),
                    null,
                    null);
        }
        var snapshot = decode(receipt, row);
        binding(row, snapshot.command());
        return new View(
                "FROZEN",
                type,
                orderId,
                row.getReinspectionTaskId(),
                row.getTenantId(),
                row.getFactoryCode(),
                receipt.getPayloadFingerprint(),
                snapshot);
    }

    private Snapshot decode(QmsReworkCompletionReceiptEntity receipt, QmsReworkOrderEntity row) {
        String payload = receipt.getPayload();
        require(
                payload != null
                        && payload.getBytes(StandardCharsets.UTF_8).length <= 65536
                        && Objects.equals(sha(payload), receipt.getPayloadFingerprint()),
                "完工回执原文指纹损坏");
        var snapshot = read(payload);
        require(snapshot != null && write(snapshot).equals(payload), "完工回执不是本版本的确定性原文");
        validate(snapshot.command());
        require(
                primary(receipt.getId())
                        && Objects.equals(receipt.getTenantId(), row.getTenantId())
                        && Objects.equals(receipt.getFactoryCode(), row.getFactoryCode())
                        && Objects.equals(receipt.getInspectionType(), row.getInspectionType())
                        && Objects.equals(receipt.getOrderId(), row.getId())
                        && Objects.equals(receipt.getChildTaskId(), row.getReinspectionTaskId())
                        && Objects.equals(receipt.getRequestId(), row.getRequestId())
                        && snapshot.receivedAt() > 0
                        && receipt.getReceivedAt() != null
                        && receipt.getReceivedAt().getTime() == snapshot.receivedAt()
                        && Objects.equals(receipt.getReceivedBy(), snapshot.receivedBy())
                        && (snapshot.receivedBy() == null || primary(snapshot.receivedBy()))
                        && snapshot(
                                        row,
                                        snapshot.command(),
                                        snapshot.receivedBy(),
                                        snapshot.receivedAt())
                                .equals(snapshot),
                "完工回执与原协调身份、接收时间或索引不一致");
        return snapshot;
    }

    private void binding(QmsReworkOrderEntity row, Command command) {
        require(
                primary(row.getInspectionTaskId())
                        && primary(row.getReinspectionTaskId())
                        && !Objects.equals(row.getInspectionTaskId(), row.getReinspectionTaskId())
                        && primary(row.getHandleRecordId())
                        && code(row.getRequestId())
                        && Set.of(
                                        "REINSPECTION_PENDING",
                                        "RESULT_SYNC_PENDING",
                                        "RESULT_SYNC_FAILED",
                                        "CLOSED")
                                .contains(Objects.toString(row.getStatus(), ""))
                        && row.getQuantity() != null
                        && row.getQuantity().signum() > 0
                        && row.getMesCompletedQuantity() != null
                        && row.getQuantity().compareTo(row.getMesCompletedQuantity()) == 0,
                "原协调单缺少有效完工与复检事实");
        var ids = List.of(row.getInspectionTaskId(), row.getReinspectionTaskId());
        var actual =
                "PRODUCT".equals(row.getInspectionType())
                        ? tasks.productTasks(row.getTenantId(), row.getFactoryCode(), ids)
                        : tasks.procedureTasks(row.getTenantId(), row.getFactoryCode(), ids);
        require(actual != null && actual.size() == 2, "回执原检验或复检缺失、已删除或异组织");
        var map = new HashMap<Long, com.kitex.mom.qms.dto.QmsInspectionTraceRows.Task>();
        for (var task : actual)
            require(
                    task != null
                            && ids.contains(task.getId())
                            && Objects.equals(task.getTenantId(), row.getTenantId())
                            && Objects.equals(task.getFactoryCode(), row.getFactoryCode())
                            && map.put(task.getId(), task) == null,
                    "回执实际任务身份不一致");
        var parent = map.get(row.getInspectionTaskId());
        var child = map.get(row.getReinspectionTaskId());
        var handles =
                "PRODUCT".equals(row.getInspectionType())
                        ? tasks.productHandles(
                                row.getTenantId(),
                                row.getFactoryCode(),
                                List.of(row.getHandleRecordId()))
                        : tasks.procedureHandles(
                                row.getTenantId(),
                                row.getFactoryCode(),
                                List.of(row.getHandleRecordId()));
        require(handles != null && handles.size() == 1, "回执原返工处置缺失、已删除或异组织");
        var handle = handles.get(0);
        require(
                handle != null
                        && Objects.equals(handle.getId(), row.getHandleRecordId())
                        && Objects.equals(handle.getTaskId(), row.getInspectionTaskId())
                        && Objects.equals(handle.getTenantId(), row.getTenantId())
                        && Objects.equals(handle.getFactoryCode(), row.getFactoryCode())
                        && "REWORK".equals(handle.getHandleResult())
                        && handle.getHandleNum() != null
                        && row.getQuantity()
                                        .compareTo(
                                                java.math.BigDecimal.valueOf(handle.getHandleNum()))
                                == 0
                        && Objects.equals(
                                row.getReason(),
                                cn.hutool.core.util.StrUtil.blankToDefault(
                                        cn.hutool.core.util.StrUtil.trim(handle.getHandleRemark()),
                                        "质量不合格返工")),
                "回执实际处置来源、数量或原因不一致");
        require(
                parent != null
                        && child != null
                        && Objects.equals(parent.getOrderNo(), row.getOriginalMesTaskCode())
                        && Objects.equals(
                                parent.getSourceTaskCode(), row.getOriginalMesProduceTaskCode())
                        && Objects.equals(parent.getProcessCode(), row.getReworkProcessCode())
                        && Objects.equals(parent.getMatCode(), row.getMatCode())
                        && Objects.equals(parent.getBatchNo(), row.getBatchNo())
                        && Objects.equals(
                                child.getSourceRequestId(),
                                "REWORK-REINSPECTION:" + row.getRequestId())
                        && Objects.equals(child.getOrderNo(), row.getMesTaskCode())
                        && code(child.getSourceTaskCode())
                        && Objects.equals(child.getMatCode(), row.getMatCode())
                        && Objects.equals(child.getBatchNo(), row.getBatchNo())
                        && Objects.equals(child.getProcessCode(), row.getReworkProcessCode())
                        && child.getNum() != null
                        && row.getQuantity().compareTo(java.math.BigDecimal.valueOf(child.getNum()))
                                == 0
                        && Objects.equals(child.getStandardId(), parent.getStandardId())
                        && Objects.equals(child.getDeptCode(), parent.getDeptCode()),
                "原协调单、实际检验来源或完工数量不一致");
        if (command != null)
            require(
                    Objects.equals(command.requestId(), row.getRequestId())
                            && Objects.equals(command.mesTaskCode(), row.getMesTaskCode())
                            && Objects.equals(
                                    command.mesProduceTaskCode(), child.getSourceTaskCode())
                            && Objects.equals(command.processCode(), child.getProcessCode())
                            && Objects.equals(command.processName(), child.getProcessName())
                            && Objects.equals(command.completedQuantity(), child.getNum()),
                    "原始完工回执与实际复检身份或数量不一致");
    }

    private QmsReworkOrderEntity order(String type, Long id, boolean lock) {
        type(type);
        require(primary(id), "返工协调单编号无效");
        var row =
                orders.selectOne(
                        new LambdaQueryWrapper<QmsReworkOrderEntity>()
                                .eq(QmsReworkOrderEntity::getId, id)
                                .eq(
                                        QmsReworkOrderEntity::getTenantId,
                                        TenantContextHolder.getTenantId())
                                .eq(
                                        QmsReworkOrderEntity::getFactoryCode,
                                        FactoryContextHolder.getFactoryCode())
                                .eq(QmsReworkOrderEntity::getInspectionType, type)
                                .last(lock ? "FOR UPDATE" : ""));
        require(row != null, "当前实际组织不存在该类返工协调单");
        return row;
    }

    private QmsReworkCompletionReceiptEntity find(Long id) {
        require(primary(id), "完工回执缺少协调编号");
        var rows =
                mapper.byOrder(
                        TenantContextHolder.getTenantId(),
                        FactoryContextHolder.getFactoryCode(),
                        id);
        require(rows != null && rows.size() <= 1, "同一协调单存在多个原始回执");
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void ready() {
        try {
            require(Objects.equals(mapper.schemaVersion(), 1), "完工回执冻结结构未就绪，请完成对应数据库升级");
        } catch (org.springframework.dao.DataAccessException missing) {
            throw new CheckedException("完工回执冻结结构未就绪，请完成对应数据库升级", missing);
        }
    }

    private static void type(String type) {
        require("PRODUCT".equals(type) || "PROCEDURE".equals(type), "不支持的检验回执类型");
    }

    private static boolean primary(Long id) {
        return id != null && id > 0;
    }
}
