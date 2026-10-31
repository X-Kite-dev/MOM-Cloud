package com.kitex.mom.qms.service.impl;

import cn.hutool.core.util.StrUtil;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.MesTaskReceiveDTO;
import com.kitex.mom.qms.dto.QmsReworkCompletionDTO;
import com.kitex.mom.qms.dto.inspection.InspectionProcedureCreateParam;
import com.kitex.mom.qms.dto.inspection.InspectionProductCreateParam;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.mapper.*;
import com.kitex.mom.qms.service.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** 原处置、MES返工及唯一复检的本域协调；不授予原始首检指标资格。 */
@Service
@RequiredArgsConstructor
public class QmsReworkOrderServiceImpl
        extends ServiceImpl<QmsReworkOrderMapper, QmsReworkOrderEntity>
        implements QmsReworkOrderService {
    private final QmsInspectionProcedureHandleRecordMapper procedureHandleMapper;
    private final QmsInspectionProductHandleRecordMapper productHandleMapper;
    private final QmsInspectionProcedureTaskMapper procedureTaskMapper;
    private final QmsInspectionProductTaskMapper productTaskMapper;
    private final RemoteMesTaskService remoteMesTaskService;
    private final InspectionProcedureTaskService procedureTaskService;
    private final InspectionProductTaskService productTaskService;
    private final QmsReworkResultPublisher resultPublisher;
    private final QmsInspectionHistorySupport access;
    private final PermissionService permissions;
    private final QmsReworkCompletionReceiptService completionReceipts;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QmsReworkOrderEntity createManual(String inspectionType, Long handleRecordId) {
        manual(inspectionType);
        return create(inspectionType, handleRecordId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QmsReworkOrderEntity retryManual(Long id) {
        access.requireWrite();
        var row = order(id, false);
        manual(row.getInspectionType());
        return retry(id);
    }

    private void manual(String type) {
        access.requireWrite();
        type = type(type);
        require(
                permissions.hasPermission(
                        "PROCEDURE".equals(type) ? "procedureTask:handle" : "productTask:handle"),
                "当前人员没有对应检验类型的返工处置权限");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QmsReworkOrderEntity create(String inspectionType, Long handleRecordId) {
        access.requireScope();
        String type = type(inspectionType);
        var source = source(type, handleRecordId, true);
        var existing =
                getOne(
                        orderScope()
                                .eq(QmsReworkOrderEntity::getInspectionType, type)
                                .eq(QmsReworkOrderEntity::getHandleRecordId, handleRecordId)
                                .last("FOR UPDATE"));
        if (existing != null) {
            verifySource(existing, source);
            return retryable(existing) ? dispatch(existing) : existing;
        }
        MomUser user = SecurityUtils.getUser();
        var row =
                new QmsReworkOrderEntity()
                        .setRequestId("QMS-RW-" + type + "-" + handleRecordId)
                        .setInspectionType(type)
                        .setInspectionTaskId(source.inspectionTaskId())
                        .setHandleRecordId(handleRecordId)
                        .setOriginalMesTaskCode(source.originalMesTaskCode())
                        .setOriginalMesProduceTaskCode(source.originalMesProduceTaskCode())
                        .setReworkProcessCode(source.processCode())
                        .setMatCode(source.matCode())
                        .setMatName(source.matName())
                        .setMatSpec(source.matSpec())
                        .setBatchNo(source.batchNo())
                        .setQuantity(BigDecimal.valueOf(source.quantity()))
                        .setReason(source.reason())
                        .setStatus("DISPATCHING")
                        .setDispatchCount(0)
                        .setCallbackCount(0)
                        .setInitiatedBy(user == null ? "SYSTEM" : user.getUsername())
                        .setInitiatedByName(user == null ? "系统" : user.getName())
                        .setInitiatedTime(LocalDateTime.now())
                        .setFactoryCode(FactoryContextHolder.getFactoryCode());
        row.setTenantId(TenantContextHolder.getTenantId());
        require(save(row) && row.getId() != null && row.getId() > 0, "返工协调单保存失败，未下发MES");
        return dispatch(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QmsReworkOrderEntity retry(Long id) {
        access.requireScope();
        var initial = order(id, false);
        // 与create保持处置行 -> 协调单的锁序，不能在持协调单后反向等待处置行。
        var source = source(type(initial.getInspectionType()), initial.getHandleRecordId(), true);
        var row = order(id, true);
        verifySource(row, source);
        return retryable(row) ? dispatch(row) : row;
    }

    private boolean retryable(QmsReworkOrderEntity row) {
        return Set.of("DISPATCH_FAILED", "DISPATCHING")
                .contains(Objects.toString(row.getStatus(), ""));
    }

    private QmsReworkOrderEntity dispatch(QmsReworkOrderEntity row) {
        require(
                row.getReinspectionTaskId() == null && StrUtil.isBlank(row.getMesTaskCode()),
                "已有MES工单或复检的协调单不能重新下发");
        row.setDispatchCount(
                        Math.addExact(Objects.requireNonNullElse(row.getDispatchCount(), 0), 1))
                .setStatus("DISPATCHING")
                .setLastError(null);
        write(row);
        var command = new MesTaskReceiveDTO();
        command.setReworkRequestId(row.getRequestId());
        command.setSourceTaskCode(row.getOriginalMesTaskCode());
        command.setSourceProduceTaskCode(row.getOriginalMesProduceTaskCode());
        command.setSourceInspectionType(row.getInspectionType());
        command.setSourceInspectionTaskId(row.getInspectionTaskId());
        command.setSourceHandleRecordId(row.getHandleRecordId());
        command.setReworkProcessCode(row.getReworkProcessCode());
        command.setReworkReason(row.getReason());
        command.setMatCode(row.getMatCode());
        command.setMatName(row.getMatName());
        command.setMatSpec(row.getMatSpec());
        command.setNoticeNum(row.getQuantity());
        command.setResourceType("REWORK");
        command.setResourceId(row.getInspectionTaskId());
        R<String> response;
        try {
            response = remoteMesTaskService.addRework(command);
        } catch (RuntimeException failure) {
            row.setStatus("DISPATCH_FAILED").setLastError(rootMessage(failure));
            write(row);
            return row;
        }
        if (response == null || !response.isOk() || !validCode(response.getData())) {
            row.setStatus("DISPATCH_FAILED")
                    .setLastError(
                            response == null
                                    ? "MES未返回返工工单"
                                    : StrUtil.maxLength(
                                            StrUtil.blankToDefault(
                                                    response.getMsg(), "MES返工响应无有效工单号"),
                                            1000));
            write(row);
            return row;
        }
        row.setMesTaskCode(response.getData()).setStatus("MES_CREATED").setLastError(null);
        markHandleDispatched(row);
        write(row);
        return row;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long completeFromMes(QmsReworkCompletionDTO command) {
        access.requireScope();
        require(
                command != null
                        && validCode(command.getRequestId())
                        && validCode(command.getMesTaskCode())
                        && validCode(command.getMesProduceTaskCode())
                        && validCode(command.getProcessCode()),
                "MES返工回传必须提供原请求、返工工单、最终工序和主工序");
        var row =
                getOne(
                        orderScope()
                                .eq(QmsReworkOrderEntity::getRequestId, command.getRequestId())
                                .last("FOR UPDATE"));
        require(
                row != null && Objects.equals(row.getMesTaskCode(), command.getMesTaskCode()),
                "未找到匹配的返工协调单");
        var source = source(type(row.getInspectionType()), row.getHandleRecordId(), false);
        verifySource(row, source);
        require(
                command.getCompletedQuantity() != null
                        && row.getQuantity() != null
                        && row.getQuantity().signum() > 0
                        && command.getCompletedQuantity().compareTo(row.getQuantity()) == 0,
                "MES返工完工量必须与本笔处置量完全一致");
        require(
                StrUtil.isBlank(row.getReworkProcessCode())
                        || Objects.equals(row.getReworkProcessCode(), command.getProcessCode()),
                "MES返工主工序与原处置要求不一致");
        var original = inspection(row.getInspectionType(), row.getInspectionTaskId());
        if (row.getReinspectionTaskId() != null) {
            completionReceipts.verifyReplay(row, command);
            require(
                    row.getReinspectionTaskId() > 0
                            && row.getMesCompletedQuantity() != null
                            && row.getMesCompletedQuantity()
                                            .compareTo(command.getCompletedQuantity())
                                    == 0,
                    "原复检关联或完工数量不完整，请核对原单");
            verifyChild(
                    row,
                    original,
                    command,
                    inspection(row.getInspectionType(), row.getReinspectionTaskId()));
            return row.getReinspectionTaskId();
        }
        require("MES_CREATED".equals(row.getStatus()), "协调单尚未取得MES返工工单，不能生成复检");
        completionReceipts.requireAbsent(row, command);
        int quantity;
        try {
            quantity = row.getQuantity().intValueExact();
        } catch (ArithmeticException invalid) {
            throw new CheckedException("复检数量必须为可支持的正整数", invalid);
        }
        String sourceRequestId = "REWORK-REINSPECTION:" + row.getRequestId();
        Long id;
        if ("PROCEDURE".equals(row.getInspectionType())) {
            id =
                    procedureTaskService.create(
                            new InspectionProcedureCreateParam()
                                    .setSourceRequestId(sourceRequestId)
                                    .setSourceTaskCode(command.getMesProduceTaskCode())
                                    .setTaskCode(command.getMesProduceTaskCode())
                                    .setMatCode(row.getMatCode())
                                    .setMatName(row.getMatName())
                                    .setMatSpec(row.getMatSpec())
                                    .setProcessCode(command.getProcessCode())
                                    .setProcessName(command.getProcessName())
                                    .setStandardId(original.standardId())
                                    .setBatchNo(row.getBatchNo())
                                    .setNum(quantity)
                                    .setInspectionNum(original.inspectionNum())
                                    .setLabelType(original.label())
                                    .setDeptCode(original.dept())
                                    .setOrderNo(row.getMesTaskCode()));
        } else {
            productTaskService.create(
                    new InspectionProductCreateParam()
                            .setSourceRequestId(sourceRequestId)
                            .setSourceTaskCode(command.getMesProduceTaskCode())
                            .setName("返工复检-" + row.getMesTaskCode())
                            .setMatCode(row.getMatCode())
                            .setMatName(row.getMatName())
                            .setMatSpec(row.getMatSpec())
                            .setProcessCode(command.getProcessCode())
                            .setProcessName(command.getProcessName())
                            .setStandardId(original.standardId())
                            .setBatchNo(row.getBatchNo())
                            .setNum(quantity)
                            .setInspectionNum(original.inspectionNum())
                            .setDeptCode(original.dept())
                            .setOrderNo(row.getMesTaskCode()));
            var child =
                    productTaskMapper.selectOne(
                            new LambdaQueryWrapper<QmsInspectionProductTaskEntity>()
                                    .eq(
                                            QmsInspectionProductTaskEntity::getSourceRequestId,
                                            sourceRequestId)
                                    .eq(
                                            QmsInspectionProductTaskEntity::getTenantId,
                                            TenantContextHolder.getTenantId())
                                    .eq(
                                            QmsInspectionProductTaskEntity::getFactoryCode,
                                            FactoryContextHolder.getFactoryCode()));
            require(child != null, "成品复检生成后未找到实际任务");
            id = child.getId();
        }
        require(id != null && id > 0, "复检生成未返回有效任务编号");
        verifyChild(row, original, command, inspection(row.getInspectionType(), id));
        row.setMesCompletedQuantity(command.getCompletedQuantity())
                .setMesCompletedTime(LocalDateTime.now())
                .setReinspectionTaskId(id)
                .setReinspectionStatus("WAIT")
                .setReinspectionCreatedTime(LocalDateTime.now())
                .setStatus("REINSPECTION_PENDING")
                .setCallbackCount(
                        Math.addExact(Objects.requireNonNullElse(row.getCallbackCount(), 0), 1))
                .setLastError(null);
        write(row);
        completionReceipts.record(row, command);
        return id;
    }

    private void verifyChild(
            QmsReworkOrderEntity row,
            Inspection original,
            QmsReworkCompletionDTO command,
            Inspection child) {
        require(
                child != null
                        && Objects.equals(
                                child.request(), "REWORK-REINSPECTION:" + row.getRequestId())
                        && Objects.equals(child.source(), command.getMesProduceTaskCode())
                        && Objects.equals(child.order(), row.getMesTaskCode())
                        && Objects.equals(child.process(), command.getProcessCode())
                        && Objects.equals(child.processName(), command.getProcessName())
                        && Objects.equals(child.mat(), row.getMatCode())
                        && Objects.equals(child.batch(), row.getBatchNo())
                        && Objects.equals(child.standardId(), original.standardId())
                        && Objects.equals(child.dept(), original.dept())
                        && Objects.equals(child.label(), original.label())
                        && child.quantity() != null
                        && row.getQuantity().compareTo(BigDecimal.valueOf(child.quantity())) == 0
                        && child.inspectionNum() != null
                        && child.inspectionNum() > 0
                        && child.inspectionNum() <= child.quantity(),
                "复检任务与原处置、MES工序或完工数量不一致，禁止重复成功");
    }

    @Override
    public void retryResultSync(Long id) {
        resultPublisher.retry(id);
    }

    private LambdaQueryWrapper<QmsReworkOrderEntity> orderScope() {
        return new LambdaQueryWrapper<QmsReworkOrderEntity>()
                .eq(QmsReworkOrderEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(QmsReworkOrderEntity::getFactoryCode, FactoryContextHolder.getFactoryCode());
    }

    private QmsReworkOrderEntity order(Long id, boolean lock) {
        require(id != null && id > 0, "返工协调单编号无效");
        var row =
                getOne(
                        orderScope()
                                .eq(QmsReworkOrderEntity::getId, id)
                                .last(lock ? "FOR UPDATE" : ""));
        require(row != null, "当前实际组织不存在返工协调单");
        return row;
    }

    private void write(QmsReworkOrderEntity row) {
        var update =
                new LambdaUpdateWrapper<QmsReworkOrderEntity>()
                        .eq(QmsReworkOrderEntity::getId, row.getId())
                        .eq(QmsReworkOrderEntity::getTenantId, TenantContextHolder.getTenantId())
                        .eq(
                                QmsReworkOrderEntity::getFactoryCode,
                                FactoryContextHolder.getFactoryCode());
        if (row.getLastError() == null) update.set(QmsReworkOrderEntity::getLastError, null);
        require(update(row, update), "返工协调事实保存失败");
    }

    private void markHandleDispatched(QmsReworkOrderEntity row) {
        int affected =
                "PROCEDURE".equals(row.getInspectionType())
                        ? procedureHandleMapper.update(
                                null,
                                new LambdaUpdateWrapper<QmsInspectionProcedureHandleRecordEntity>()
                                        .eq(
                                                QmsInspectionProcedureHandleRecordEntity::getId,
                                                row.getHandleRecordId())
                                        .eq(
                                                QmsInspectionProcedureHandleRecordEntity::getTaskId,
                                                row.getInspectionTaskId())
                                        .eq(
                                                QmsInspectionProcedureHandleRecordEntity
                                                        ::getTenantId,
                                                TenantContextHolder.getTenantId())
                                        .eq(
                                                QmsInspectionProcedureHandleRecordEntity
                                                        ::getFactoryCode,
                                                FactoryContextHolder.getFactoryCode())
                                        .set(
                                                QmsInspectionProcedureHandleRecordEntity::getStatus,
                                                true))
                        : productHandleMapper.update(
                                null,
                                new LambdaUpdateWrapper<QmsInspectionProductHandleRecordEntity>()
                                        .eq(
                                                QmsInspectionProductHandleRecordEntity::getId,
                                                row.getHandleRecordId())
                                        .eq(
                                                QmsInspectionProductHandleRecordEntity::getTaskId,
                                                row.getInspectionTaskId())
                                        .eq(
                                                QmsInspectionProductHandleRecordEntity::getTenantId,
                                                TenantContextHolder.getTenantId())
                                        .eq(
                                                QmsInspectionProductHandleRecordEntity
                                                        ::getFactoryCode,
                                                FactoryContextHolder.getFactoryCode())
                                        .set(
                                                QmsInspectionProductHandleRecordEntity::getStatus,
                                                true));
        require(affected == 1, "返工处置下发状态保存失败");
    }

    private Source source(String type, Long handleId, boolean lock) {
        require(handleId != null && handleId > 0, "处置记录编号无效");
        Long taskId;
        Integer quantity;
        String reason;
        if ("PROCEDURE".equals(type)) {
            var row =
                    procedureHandleMapper.selectOne(
                            new LambdaQueryWrapper<QmsInspectionProcedureHandleRecordEntity>()
                                    .eq(QmsInspectionProcedureHandleRecordEntity::getId, handleId)
                                    .eq(
                                            QmsInspectionProcedureHandleRecordEntity::getTenantId,
                                            TenantContextHolder.getTenantId())
                                    .eq(
                                            QmsInspectionProcedureHandleRecordEntity
                                                    ::getFactoryCode,
                                            FactoryContextHolder.getFactoryCode())
                                    .last(lock ? "FOR UPDATE" : ""));
            require(row != null && "REWORK".equals(row.getHandleResult()), "未找到同组织的工序返工处置记录");
            taskId = row.getTaskId();
            quantity = row.getHandleNum();
            reason = row.getHandleRemark();
        } else {
            var row =
                    productHandleMapper.selectOne(
                            new LambdaQueryWrapper<QmsInspectionProductHandleRecordEntity>()
                                    .eq(QmsInspectionProductHandleRecordEntity::getId, handleId)
                                    .eq(
                                            QmsInspectionProductHandleRecordEntity::getTenantId,
                                            TenantContextHolder.getTenantId())
                                    .eq(
                                            QmsInspectionProductHandleRecordEntity::getFactoryCode,
                                            FactoryContextHolder.getFactoryCode())
                                    .last(lock ? "FOR UPDATE" : ""));
            require(row != null && "REWORK".equals(row.getHandleResult()), "未找到同组织的成品返工处置记录");
            taskId = row.getTaskId();
            quantity = row.getHandleNum();
            reason = row.getHandleRemark();
        }
        var original = inspection(type, taskId);
        require(
                validCode(original.order())
                        && original.quantity() != null
                        && quantity != null
                        && quantity > 0
                        && quantity <= original.quantity(),
                "返工处置缺少原工单或有效数量");
        require(
                original.handled() != null
                        && original.usable() != null
                        && original.usable() >= 0
                        && original.usable() <= original.handled()
                        && original.handled() <= original.quantity()
                        && quantity <= original.handled() - original.usable(),
                "原检验已处置、可用和返工数量不守恒，请核对原处置记录");
        return new Source(
                taskId,
                original.order(),
                original.source(),
                original.process(),
                original.mat(),
                original.matName(),
                original.matSpec(),
                original.batch(),
                quantity,
                StrUtil.blankToDefault(StrUtil.trim(reason), "质量不合格返工"));
    }

    private Inspection inspection(String type, Long id) {
        require(id != null && id > 0, "原检验或复检任务编号无效");
        if ("PROCEDURE".equals(type)) {
            var r =
                    procedureTaskMapper.selectOne(
                            new LambdaQueryWrapper<QmsInspectionProcedureTaskEntity>()
                                    .eq(QmsInspectionProcedureTaskEntity::getId, id)
                                    .eq(
                                            QmsInspectionProcedureTaskEntity::getTenantId,
                                            TenantContextHolder.getTenantId())
                                    .eq(
                                            QmsInspectionProcedureTaskEntity::getFactoryCode,
                                            FactoryContextHolder.getFactoryCode()));
            require(r != null, "当前实际组织不存在工序检验任务");
            return new Inspection(
                    r.getSourceRequestId(),
                    r.getSourceTaskCode(),
                    r.getOrderNo(),
                    r.getMatCode(),
                    r.getMatName(),
                    r.getMatSpec(),
                    r.getProcessCode(),
                    r.getProcessName(),
                    r.getBatchNo(),
                    r.getStandardId(),
                    r.getNum(),
                    r.getInspectionNum(),
                    r.getDeptCode(),
                    r.getLabelType(),
                    r.getHandleNum(),
                    r.getUsableNum());
        }
        var r =
                productTaskMapper.selectOne(
                        new LambdaQueryWrapper<QmsInspectionProductTaskEntity>()
                                .eq(QmsInspectionProductTaskEntity::getId, id)
                                .eq(
                                        QmsInspectionProductTaskEntity::getTenantId,
                                        TenantContextHolder.getTenantId())
                                .eq(
                                        QmsInspectionProductTaskEntity::getFactoryCode,
                                        FactoryContextHolder.getFactoryCode()));
        require(r != null, "当前实际组织不存在成品检验任务");
        return new Inspection(
                r.getSourceRequestId(),
                r.getSourceTaskCode(),
                r.getOrderNo(),
                r.getMatCode(),
                r.getMatName(),
                r.getMatSpec(),
                r.getProcessCode(),
                r.getProcessName(),
                r.getBatchNo(),
                r.getStandardId(),
                r.getNum(),
                r.getInspectionNum(),
                r.getDeptCode(),
                null,
                r.getHandleNum(),
                r.getUsableNum());
    }

    private void verifySource(QmsReworkOrderEntity row, Source s) {
        require(
                Objects.equals(row.getTenantId(), TenantContextHolder.getTenantId())
                        && Objects.equals(
                                row.getFactoryCode(), FactoryContextHolder.getFactoryCode())
                        && Objects.equals(row.getInspectionTaskId(), s.inspectionTaskId())
                        && Objects.equals(row.getOriginalMesTaskCode(), s.originalMesTaskCode())
                        && Objects.equals(
                                row.getOriginalMesProduceTaskCode(), s.originalMesProduceTaskCode())
                        && Objects.equals(row.getReworkProcessCode(), s.processCode())
                        && Objects.equals(row.getMatCode(), s.matCode())
                        && Objects.equals(row.getMatName(), s.matName())
                        && Objects.equals(row.getMatSpec(), s.matSpec())
                        && Objects.equals(row.getBatchNo(), s.batchNo())
                        && Objects.equals(row.getReason(), s.reason())
                        && row.getQuantity() != null
                        && row.getQuantity().compareTo(BigDecimal.valueOf(s.quantity())) == 0,
                "返工协调单与原质量处置事实不一致，请核对原单");
    }

    private static String type(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        require(Set.of("PROCEDURE", "PRODUCT").contains(value), "仅支持工序检、成品检返工；采购返工须走供应商或委外流程");
        return value;
    }

    private static boolean validCode(String value) {
        return value != null
                && !value.isBlank()
                && value.length() <= 200
                && value.equals(value.trim())
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }

    private String rootMessage(Throwable failure) {
        while (failure.getCause() != null && failure.getCause() != failure)
            failure = failure.getCause();
        return StrUtil.maxLength(
                StrUtil.blankToDefault(failure.getMessage(), failure.getClass().getSimpleName()),
                1000);
    }

    private record Source(
            Long inspectionTaskId,
            String originalMesTaskCode,
            String originalMesProduceTaskCode,
            String processCode,
            String matCode,
            String matName,
            String matSpec,
            String batchNo,
            Integer quantity,
            String reason) {}

    private record Inspection(
            String request,
            String source,
            String order,
            String mat,
            String matName,
            String matSpec,
            String process,
            String processName,
            String batch,
            Long standardId,
            Integer quantity,
            Integer inspectionNum,
            String dept,
            String label,
            Integer handled,
            Integer usable) {}
}
