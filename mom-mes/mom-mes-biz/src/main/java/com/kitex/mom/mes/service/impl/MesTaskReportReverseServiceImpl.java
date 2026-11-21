package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesTaskReportReverseVO;
import com.kitex.mom.mes.dto.MesWarehouseLiveStockInoutDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.*;
import com.kitex.mom.mes.service.*;
import com.kitex.mom.mes.util.MesFrozenExecutionGraph;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 报工冲销服务。以反向业务事务恢复现场状态，不删除或改写原始业务事实。
 */
@Service
@RequiredArgsConstructor
public class MesTaskReportReverseServiceImpl implements MesTaskReportReverseService {

    private final MesTaskReportService reportService;
    private final MesTaskReportRecordService recordService;
    private final MesTaskArrangeService arrangeService;
    private final MesProduceTaskService produceTaskService;
    private final MesTaskService taskService;
    private final MesInboundAllocationService inboundAllocationService;
    private final MesWarehouseInoutService inoutService;
    private final MesWarehouseLiveStockService liveStockService;
    private final com.kitex.mom.mes.mapper.MesWipTransferMapper transfers;
    private final MesWarehouseFeedService feedService;
    private final MesReportMaterialConsumeService consumeService;
    private final MesWrapRecordService wrapRecordService;
    private final MesReportQuantityCorrectionLineService correctionLineService;
    private final com.kitex.mom.mes.mapper.MesTravelerMapper travelerMapper;
    private final MesActualFactoryAccessService factoryAccess;
    private final MesExecutionTaskLock taskLock;
    private final MesIdentifierBindingService identifierBindings;
    private final MesIdentifierBindingInverseCoordinator bindingInverse;
    private final MesTerminalExecutionGuard terminalExecutionGuard;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reverse(MesTaskReportReverseVO command) {
        validateCommand(command);
        String reverseRequestId = normalizeRequestId(command.getRequestId());
        String factory = factoryAccess.require("write");
        MesTaskReportEntity ownership = taskLock.report(command.getReportId());
        bindingInverse.lockForReport(ownership);
        MesTaskReportEntity report = reportService.getOne(new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskReportEntity::getFactoryCode, factory)
                .eq(MesTaskReportEntity::getId, command.getReportId()).last("FOR UPDATE"));
        if (report == null) {
            throw new CheckedException("未找到原报工记录");
        }
        taskLock.assertTaskCode(ownership.getTaskCode(), report.getTaskCode());
        if (ReportStatusEnum.REVERSED.name().equals(report.getStatus())) {
            if (Objects.equals(report.getReverseRequestId(), reverseRequestId)) {
                return;
            }
            throw new CheckedException("该报工已冲销，不能重复操作");
        }
        if (!ReportStatusEnum.EFFECTIVE.name().equals(report.getStatus())) {
            throw new CheckedException("历史报工缺少可逆事务关联，不能自动冲销，请走人工调整流程");
        }
        MesIdentifierBindingInverseCoordinator.Prepared bindingCompensation = bindingInverse.prepare(report, command);
        if (bindingCompensation == null) identifierBindings.assertLegacyCorrectionAllowed(report);
        if (!QualityQuantityStatusEnum.NOT_REQUIRED.name().equals(report.getQualityQuantityStatus())) {
            throw new CheckedException("该报工已进入质量数量判定，必须先完成QMS判定逆向，禁止直接冲销");
        }
        MesTaskReportEntity requestOwner = reportService.getOne(new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getReverseRequestId, reverseRequestId));
        if (requestOwner != null && !Objects.equals(requestOwner.getId(), report.getId())) {
            throw new CheckedException("冲销请求号已被其他业务使用，请刷新后重新提交");
        }

        MesTaskArrangeEntity arrange = requireArrange(report.getArrangeCode());
        terminalExecutionGuard.assertUnoccupied(arrange);
        if (liveStockService.count(new LambdaQueryWrapper<com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity>()
                .eq(com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity::getSourceReportId, report.getId())
                .eq(com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity::getTenantId, report.getTenantId())
                .eq(com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity::getFactoryCode, report.getFactoryCode())
                .isNotNull(com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity::getQualityCaseId)) > 0
                || transfers.selectCount(new LambdaQueryWrapper<com.kitex.mom.mes.entity.MesWipTransferEntity>()
                .eq(com.kitex.mom.mes.entity.MesWipTransferEntity::getSourceReportId, report.getId())
                .eq(com.kitex.mom.mes.entity.MesWipTransferEntity::getTenantId, report.getTenantId())
                .eq(com.kitex.mom.mes.entity.MesWipTransferEntity::getFactoryCode, report.getFactoryCode())
                .and(w -> w.ne(com.kitex.mom.mes.entity.MesWipTransferEntity::getStatus, "CLOSED")
                        .or().gt(com.kitex.mom.mes.entity.MesWipTransferEntity::getLostQuantity, BigDecimal.ZERO))) > 0)
            throw new CheckedException("原报工仍有独立在途、已执行丢失核销或质量隔离案件，不能冲销使实物来源失效");
        MesProduceTaskEntity produceTask = requireProduceTask(report.getProduceTaskCode());
        MesTaskEntity task = requireTask(report.getTaskCode());
        ensureNoCompletedGoodsInbound(report, task);
        ensureNoDownstreamReport(report, arrange);
        List<MesWrapRecordEntity> contents = lockPackagingContents(report, MesReportPackagingCorrection.mode(report, arrange));
        if (!contents.isEmpty()) {
            preparePackagingReverse(report, contents);
        }

        BigDecimal reverseQuantity = effectiveQuantity(report);
        requirePositive(reverseQuantity, "原报工当前有效数量");
        reverseProductOutput(report, reverseRequestId, reverseQuantity,
                command.getReverseRequestBusinessId(), command.getEvidenceFingerprint());
        reverseMaterialConsumption(report, reverseRequestId, reverseQuantity,
                command.getReverseRequestBusinessId(), command.getEvidenceFingerprint());
        reverseUnsealedPackaging(report, contents, reverseRequestId, reverseQuantity,
                command.getReverseRequestBusinessId(), command.getEvidenceFingerprint());
        decreaseBusinessQuantities(report, arrange, produceTask, task, reverseQuantity);
        bindingInverse.complete(bindingCompensation);
        markReversed(report, command.getReason().trim(), reverseRequestId, reverseQuantity);
        restoreTravelerAfterReverse(report, arrange);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reversePartial(MesTaskReportReverseVO command, Long reverseRequestId,
                               String evidenceFingerprint) {
        validateCommand(command);
        if (command.getCorrectionQuantity() == null || command.getCorrectionQuantity().signum() <= 0) {
            throw new CheckedException("部分纠正数量必须大于0");
        }
        String operationRequestId = normalizeRequestId(command.getRequestId());
        String factory = factoryAccess.require("write");
        MesTaskReportEntity ownership = taskLock.report(command.getReportId());
        MesTaskReportEntity report = reportService.getOne(new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskReportEntity::getFactoryCode, factory)
                .eq(MesTaskReportEntity::getId, command.getReportId()).last("FOR UPDATE"));
        if (report == null || !ReportStatusEnum.EFFECTIVE.name().equals(report.getStatus())) {
            throw new CheckedException("只有当前有效的新版报工可执行部分数量纠正");
        }
        taskLock.assertTaskCode(ownership.getTaskCode(), report.getTaskCode());
        if (report.getEffectiveReportNum() == null || report.getCorrectedReportNum() == null) {
            throw new CheckedException("历史报工没有冻结有效数量谱系，禁止按输入数量猜测冲减");
        }
        identifierBindings.assertLegacyCorrectionAllowed(report);
        BigDecimal effective = effectiveQuantity(report);
        BigDecimal correction = command.getCorrectionQuantity();
        if (correction.compareTo(effective) >= 0) {
            throw new CheckedException("部分纠正数量必须小于当前有效数量；全部数量请使用完整冲销");
        }
        if (!QualityQuantityStatusEnum.NOT_REQUIRED.name().equals(report.getQualityQuantityStatus())) {
            throw new CheckedException("报工已进入质量数量判定，须先完成QMS数量逆向");
        }
        MesTaskArrangeEntity arrange = requireArrange(report.getArrangeCode());
        terminalExecutionGuard.assertUnoccupied(arrange);
        ReportModeEnum mode = MesReportPackagingCorrection.mode(report, arrange);
        if (mode != ReportModeEnum.QUANTITY && mode != ReportModeEnum.PACKAGE_QUANTITY) {
            throw new CheckedException("部分数量纠正只适用于数量报工和包装数量报工");
        }
        MesProduceTaskEntity produceTask = requireProduceTask(report.getProduceTaskCode());
        MesTaskEntity task = requireTask(report.getTaskCode());
        ensureNoCompletedGoodsInbound(report, task);
        ensureNoDownstreamReport(report, arrange);
        ensureNoIndependentPhysicalCase(report);
        List<MesWrapRecordEntity> contents = lockPackagingContents(report, mode);
        if (mode == ReportModeEnum.PACKAGE_QUANTITY) {
            String blocker = MesReportPackagingCorrection.partialBlocker(report, contents);
            if (blocker != null) {
                throw new CheckedException(blocker);
            }
        }

        reverseProductOutput(report, operationRequestId, correction, reverseRequestId, evidenceFingerprint);
        reverseMaterialConsumption(report, operationRequestId, correction, reverseRequestId, evidenceFingerprint);
        reverseUnsealedPackaging(report, contents, operationRequestId, correction,
                reverseRequestId, evidenceFingerprint);
        decreaseBusinessQuantities(report, arrange, produceTask, task, correction);
        markPartiallyCorrected(report, command.getReason().trim(), operationRequestId,
                correction, reverseRequestId, evidenceFingerprint);
    }

    private void validateCommand(MesTaskReportReverseVO command) {
        if (command == null || command.getReportId() == null) {
            throw new CheckedException("原报工记录ID不能为空");
        }
        if (StrUtil.isBlank(command.getReason())) {
            throw new CheckedException("冲销原因不能为空");
        }
        if (command.getReason().trim().length() > 500) {
            throw new CheckedException("冲销原因长度不能超过500位");
        }
    }

    private String normalizeRequestId(String requestId) {
        String normalized = StrUtil.isBlank(requestId) ? UUID.randomUUID().toString() : requestId.trim();
        if (normalized.length() > 64) {
            throw new CheckedException("冲销请求号长度不能超过64位");
        }
        return normalized;
    }

    private MesTaskArrangeEntity requireArrange(String code) {
        MesTaskArrangeEntity arrange = arrangeService.getOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, factoryAccess.require("write"))
                .eq(MesTaskArrangeEntity::getCode, code));
        if (arrange == null) {
            throw new CheckedException("原报工对应的派工任务不存在");
        }
        return arrange;
    }

    private MesProduceTaskEntity requireProduceTask(String code) {
        MesProduceTaskEntity task = produceTaskService.getOne(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesProduceTaskEntity::getFactoryCode, factoryAccess.require("write"))
                .eq(MesProduceTaskEntity::getCode, code));
        if (task == null) {
            throw new CheckedException("原报工对应的工序任务不存在");
        }
        return task;
    }

    private MesTaskEntity requireTask(String code) {
        MesTaskEntity task = taskService.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskEntity::getFactoryCode, factoryAccess.require("write"))
                .eq(MesTaskEntity::getCode, code).last("FOR UPDATE"));
        if (task == null) {
            throw new CheckedException("原报工对应的生产工单不存在");
        }
        return task;
    }

    private void ensureNoIndependentPhysicalCase(MesTaskReportEntity report) {
        if (liveStockService.count(new LambdaQueryWrapper<MesWarehouseLiveStockEntity>()
                .eq(MesWarehouseLiveStockEntity::getSourceReportId, report.getId())
                .eq(MesWarehouseLiveStockEntity::getTenantId, report.getTenantId())
                .eq(MesWarehouseLiveStockEntity::getFactoryCode, report.getFactoryCode())
                .isNotNull(MesWarehouseLiveStockEntity::getQualityCaseId)) > 0
                || transfers.selectCount(new LambdaQueryWrapper<MesWipTransferEntity>()
                .eq(MesWipTransferEntity::getSourceReportId, report.getId())
                .eq(MesWipTransferEntity::getTenantId, report.getTenantId())
                .eq(MesWipTransferEntity::getFactoryCode, report.getFactoryCode())
                .and(w -> w.ne(MesWipTransferEntity::getStatus, "CLOSED")
                        .or().gt(MesWipTransferEntity::getLostQuantity, BigDecimal.ZERO))) > 0) {
            throw new CheckedException("原报工仍有独立在途、已执行丢失核销或质量隔离案件，不能纠正使实物来源失效");
        }
    }

    /**
     * 入库红冲尚未完成前，只允许冲销没有成品入库占用且来源账完整的报工。
     */
    private void ensureNoCompletedGoodsInbound(MesTaskReportEntity report, MesTaskEntity task) {
        List<MesTaskInWmsReceiptDetailEntity> details =
                inboundAllocationService.activeByTask(task.getCode(), task.getFactoryCode(), true);
        BigDecimal tracked = details.stream().map(MesTaskInWmsReceiptDetailEntity::getAllocatedQuantity)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal actual = Objects.requireNonNullElse(task.getInStockNum(), BigDecimal.ZERO);
        if (tracked.compareTo(actual) != 0) {
            throw new CheckedException("工单已有成品入库量缺少等额报工来源明细，须先治理历史归属并完成实物核对");
        }
        BigDecimal reportInbound = details.stream().filter(item -> Objects.equals(item.getReportId(), report.getId()))
                .map(MesTaskInWmsReceiptDetailEntity::getAllocatedQuantity).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (reportInbound.signum() > 0) {
            throw new CheckedException("该报工已有成品入库分配数量"
                    + reportInbound.stripTrailingZeros().toPlainString() + "，须先完成WMS成品入库红冲");
        }
    }

    /**
     * SN/流转卡按相同标识判断；数量报工无法识别具体下游批次，因此有任一下游有效报工时保守阻断。
     */
    private void ensureNoDownstreamReport(MesTaskReportEntity report, MesTaskArrangeEntity arrange) {
        MesProduceTaskEntity current = requireProduceTask(arrange.getProduceTaskCode());
        List<MesProduceTaskEntity> processTasks = produceTaskService.list(
                new LambdaQueryWrapper<MesProduceTaskEntity>()
                        .eq(MesProduceTaskEntity::getTenantId, report.getTenantId())
                        .eq(MesProduceTaskEntity::getFactoryCode, report.getFactoryCode())
                        .eq(MesProduceTaskEntity::getTaskCode, arrange.getTaskCode()));
        if (MesFrozenExecutionGraph.NODE_EXECUTION_V1.equals(current.getRouteExecutionSchemaVersion())) {
            ensureNoNodeDownstreamReport(report, arrange, processTasks);
            return;
        }
        if (current.getRouteExecutionSchemaVersion() != null
                || processTasks.stream().anyMatch(item -> item.getRouteExecutionSchemaVersion() != null)) {
            throw new CheckedException("冲销执行图不能混合新旧或未知协议");
        }
        if (processTasks.stream().map(MesProduceTaskEntity::getRouteCode).distinct().count() != processTasks.size()
                || processTasks.stream().anyMatch(item -> StrUtil.isBlank(item.getRouteCode()))) {
            throw new CheckedException("旧实际工艺DAG含重复/缺失主工序，不能吞并节点后冲销");
        }
        Map<String, MesProduceTaskEntity> byProcess = processTasks.stream().collect(java.util.stream.Collectors.toMap(
                MesProduceTaskEntity::getRouteCode, item -> item));
        Set<String> downstreamProcesses = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>(splitCodes(current.getSuccessorCodes()));
        while (!pending.isEmpty()) {
            String code = pending.removeFirst();
            if (!downstreamProcesses.add(code)) {
                continue;
            }
            MesProduceTaskEntity next = byProcess.get(code);
            if (next == null) {
                throw new CheckedException("工艺DAG下游工序[" + code + "]缺失，不能安全冲销");
            }
            pending.addAll(splitCodes(next.getSuccessorCodes()));
        }
        if (downstreamProcesses.isEmpty()) {
            return;
        }
        // 数量报工没有逐件谱系，只能继续按整个下游工序状态保守阻断。SN/流转卡能够按相同
        // 规范标识核对真实下游报工；链式冲销从最下游逐笔执行后，不应被其他标识已开工误伤。
        if (StrUtil.isBlank(report.getTransferCode())) {
            List<String> startedDownstream = downstreamProcesses.stream().map(byProcess::get)
                    .filter(item -> Set.of("PRODUCT", "PAUSE", "FINISH").contains(item.getStatus()))
                    .map(MesProduceTaskEntity::getRouteCode).toList();
            if (!startedDownstream.isEmpty()) {
                throw new CheckedException("下游工序已开工或完成，数量报工无法证明批次归属，不能冲销："
                        + String.join(",", startedDownstream));
            }
        }
        List<String> downstreamTaskCodes = downstreamProcesses.stream().map(byProcess::get)
                .map(MesProduceTaskEntity::getCode).toList();
        List<String> downstreamArrangeCodes = arrangeService.list(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                        .eq(MesTaskArrangeEntity::getTenantId, report.getTenantId())
                        .eq(MesTaskArrangeEntity::getFactoryCode, report.getFactoryCode())
                        .eq(MesTaskArrangeEntity::getTaskCode, arrange.getTaskCode())
                        .in(MesTaskArrangeEntity::getProduceTaskCode, downstreamTaskCodes))
                .stream().map(MesTaskArrangeEntity::getCode).toList();
        if (downstreamArrangeCodes.isEmpty()) {
            return;
        }
        LambdaQueryWrapper<MesTaskReportEntity> wrapper = new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getTenantId, report.getTenantId())
                .eq(MesTaskReportEntity::getFactoryCode, report.getFactoryCode())
                .in(MesTaskReportEntity::getArrangeCode, downstreamArrangeCodes)
                .and(item -> item.isNull(MesTaskReportEntity::getStatus)
                        .or().eq(MesTaskReportEntity::getStatus, ReportStatusEnum.EFFECTIVE.name()));
        if (StrUtil.isNotBlank(report.getTransferCode())) {
            wrapper.eq(MesTaskReportEntity::getTransferCode, report.getTransferCode());
        }
        if (reportService.count(wrapper) > 0) {
            throw new CheckedException("下游工序已有相关报工，请先从最后一道已执行工序依次冲销");
        }
    }

    /**
     * 新合同仅从原完整实际图取后裔，不把同主工序另一节点当作当前或下游。
     */
    private void ensureNoNodeDownstreamReport(MesTaskReportEntity report, MesTaskArrangeEntity arrange,
                                              List<MesProduceTaskEntity> processTasks) {
        MesFrozenExecutionGraph graph = MesFrozenExecutionGraph.of(processTasks);
        MesProduceTaskEntity current = graph.current(arrange);
        assertReportArrangement(report, arrange);
        if ("ROUTE_RETURN".equals(report.getExecutionKind())) {
            throw new CheckedException("批准退回仍须按原整段路线办理，不能套用普通节点冲销");
        }
        Map<String, Integer> depths = graph.descendantDepths(current);
        if (depths.isEmpty()) return;
        List<MesProduceTaskEntity> downstreamTasks = processTasks.stream()
                .filter(item -> depths.containsKey(item.getCode())).toList();
        ReportModeEnum mode = MesReportPackagingCorrection.mode(report, arrange);
        boolean aggregate = mode == ReportModeEnum.QUANTITY || mode == ReportModeEnum.PACKAGE_QUANTITY;
        // 数量报工没有逐件谱系；保留原任一下游实际任务已开工/完成即阻断的口径。
        // 包装数量虽有包装码，也不能据此证明后续聚合工序不含箱内产品。
        if (aggregate || StrUtil.isBlank(report.getTransferCode())) {
            List<String> started = downstreamTasks.stream()
                    .filter(item -> Set.of("PRODUCT", "PAUSE", "FINISH").contains(item.getStatus()))
                    .map(item -> item.getSourceRouteNodeCode() + "[" + item.getCode() + "]").toList();
            if (!started.isEmpty()) {
                throw new CheckedException("下游实际节点已开工或完成，数量报工无法证明批次归属，不能冲销："
                        + String.join(",", started));
            }
        }
        List<MesTaskArrangeEntity> downstreamArranges = arrangeService.list(
                new LambdaQueryWrapper<MesTaskArrangeEntity>()
                        .eq(MesTaskArrangeEntity::getTenantId, report.getTenantId())
                        .eq(MesTaskArrangeEntity::getFactoryCode, report.getFactoryCode())
                        .eq(MesTaskArrangeEntity::getTaskCode, report.getTaskCode())
                        .in(MesTaskArrangeEntity::getProduceTaskCode, depths.keySet()));
        Map<String, MesTaskArrangeEntity> byArrange = new LinkedHashMap<>();
        for (MesTaskArrangeEntity candidate : downstreamArranges) {
            graph.current(candidate);
            if (StrUtil.isBlank(candidate.getCode()) || byArrange.putIfAbsent(candidate.getCode(), candidate) != null) {
                throw new CheckedException("下游派工身份缺失/重复，不能安全冲销");
            }
        }
        // 按实际后裔任务查询，即使报告的派工关联已损坏也不能漏检后回退为无下游。
        LambdaQueryWrapper<MesTaskReportEntity> wrapper = new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getTenantId, report.getTenantId())
                .eq(MesTaskReportEntity::getFactoryCode, report.getFactoryCode())
                .eq(MesTaskReportEntity::getTaskCode, report.getTaskCode())
                .in(MesTaskReportEntity::getProduceTaskCode, depths.keySet())
                .and(item -> item.isNull(MesTaskReportEntity::getStatus)
                        .or().eq(MesTaskReportEntity::getStatus, ReportStatusEnum.EFFECTIVE.name()));
        if (!aggregate && StrUtil.isNotBlank(report.getTransferCode())) {
            wrapper.eq(MesTaskReportEntity::getTransferCode, report.getTransferCode());
        }
        // 任一事实即阻断，无需截取或推断整条逐件谱系。
        MesTaskReportEntity downstream = reportService.getOne(wrapper.last("LIMIT 1"), false);
        if (downstream != null) {
            assertReportArrangement(downstream, byArrange.get(downstream.getArrangeCode()));
            throw new CheckedException("下游实际节点已有相关报工，请先从最后一个已执行节点依次冲销");
        }
    }

    private void assertReportArrangement(MesTaskReportEntity report, MesTaskArrangeEntity arrange) {
        if (report == null || arrange == null || report.getId() == null || arrange.getId() == null
                || StrUtil.isBlank(report.getArrangeCode()) || StrUtil.isBlank(report.getProduceTaskCode())
                || !Objects.equals(report.getTenantId(), arrange.getTenantId())
                || !Objects.equals(report.getFactoryCode(), arrange.getFactoryCode())
                || !Objects.equals(report.getTaskCode(), arrange.getTaskCode())
                || !Objects.equals(report.getArrangeCode(), arrange.getCode())
                || !Objects.equals(report.getProduceTaskCode(), arrange.getProduceTaskCode())) {
            throw new CheckedException("原报告与真实派工/父任务归属不一致，不能安全冲销");
        }
    }

    private List<String> splitCodes(String value) {
        if (StrUtil.isBlank(value)) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(StrUtil::isNotBlank).distinct().toList();
    }

    /** 按报工原模式锁定本次涉及的箱内行，库存和包装分配在同一事务内使用这些行。 */
    private List<MesWrapRecordEntity> lockPackagingContents(MesTaskReportEntity report, ReportModeEnum mode) {
        if (!mode.isPackaging()) {
            return List.of();
        }
        List<MesWrapRecordEntity> records = wrapRecordService.list(
                MesReportPackagingCorrection.contents(report).last("FOR UPDATE"));
        if (records.isEmpty()) {
            throw new CheckedException("本厂包装报工缺少装箱明细，不能自动纠正");
        }
        if (records.stream().anyMatch(row -> !MesReportPackagingCorrection.matchesProduction(report, row))) {
            throw new CheckedException("包装内容与原报工的工单、工序、派工或标识不一致");
        }
        return records;
    }

    /** 完整冲销只接受尚未封装或已经完整拆出的包装数量。 */
    private void preparePackagingReverse(MesTaskReportEntity report, List<MesWrapRecordEntity> records) {
        boolean hasPacked = records.stream().anyMatch(item ->
                MesWrapRecordStatusEnum.PACKED.name().equals(item.getPackingStatus()));
        if (hasPacked) {
            throw new CheckedException("产品已封箱，请先执行拆箱后再冲销报工");
        }
        boolean stagedOnly = records.stream().allMatch(item ->
                MesWrapRecordStatusEnum.STAGED.name().equals(item.getPackingStatus())
                        || MesWrapRecordStatusEnum.REVERSED.name().equals(item.getPackingStatus()));
        if (stagedOnly) {
            return;
        }
        BigDecimal available = records.stream()
                .filter(item -> MesWrapRecordStatusEnum.UNPACKED.name().equals(item.getPackingStatus())
                        || MesWrapRecordStatusEnum.PARTIAL.name().equals(item.getPackingStatus()))
                .map(MesWrapRecordEntity::getAvailableQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (available.compareTo(effectiveQuantity(report)) != 0) {
            throw new CheckedException("包装内容已被部分重组或数量链不完整，请拆开关联目标包装后再冲销");
        }
    }

    private BigDecimal effectiveQuantity(MesTaskReportEntity report) {
        return Objects.requireNonNullElse(report.getEffectiveReportNum(), report.getReportNum());
    }

    private void requirePositive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) throw new CheckedException(name + "必须大于0");
    }

    private BigDecimal correctedFor(Long reportId, String type, Long sourceId) {
        return correctionLineService.list(new LambdaQueryWrapper<MesReportQuantityCorrectionLineEntity>()
                        .eq(MesReportQuantityCorrectionLineEntity::getReportId, reportId)
                        .eq(MesReportQuantityCorrectionLineEntity::getAllocationType, type)
                        .eq(MesReportQuantityCorrectionLineEntity::getSourceId, sourceId))
                .stream().map(MesReportQuantityCorrectionLineEntity::getCorrectionQuantity)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 按原报工累计纠正比例计算本次物料恢复量，最后一次自动吸收十进制尾差。
     */
    private BigDecimal proportionalIncrement(BigDecimal original, MesTaskReportEntity report,
                                             BigDecimal reportCorrection, BigDecimal correctedBefore) {
        requirePositive(original, "原物料分配数量");
        requirePositive(report.getReportNum(), "原报工数量");
        BigDecimal totalReportCorrected = Objects.requireNonNullElse(report.getCorrectedReportNum(), BigDecimal.ZERO)
                .add(reportCorrection);
        BigDecimal desired = totalReportCorrected.compareTo(report.getReportNum()) == 0
                ? original : original.multiply(totalReportCorrected)
                .divide(report.getReportNum(), 12, RoundingMode.HALF_UP);
        BigDecimal increment = desired.subtract(correctedBefore);
        if (increment.signum() < 0 || correctedBefore.add(increment).compareTo(original) > 0) {
            throw new CheckedException("物料纠正累计比例与原始分配不守恒");
        }
        return increment;
    }

    private void saveCorrectionLine(Long reverseRequestId, String operationRequestId,
                                    MesTaskReportEntity report, String type, Long sourceId,
                                    String sourceCode, BigDecimal original, BigDecimal correctedBefore,
                                    BigDecimal correction, String evidenceFingerprint) {
        MesReportQuantityCorrectionLineEntity line = new MesReportQuantityCorrectionLineEntity()
                .setReverseRequestId(reverseRequestId).setOperationRequestId(operationRequestId)
                .setReportId(report.getId()).setAllocationType(type).setSourceId(sourceId)
                .setSourceCode(sourceCode).setOriginalQuantity(original)
                .setCorrectedBeforeQuantity(correctedBefore).setCorrectionQuantity(correction)
                .setRemainingQuantity(original.subtract(correctedBefore).subtract(correction))
                .setQuantityUnit(report.getReportUnit()).setEvidenceFingerprint(evidenceFingerprint)
                .setFactoryCode(report.getFactoryCode());
        if (!correctionLineService.save(line)) {
            throw new CheckedException("报工数量纠正分配行保存失败");
        }
    }

    private void reverseProductOutput(MesTaskReportEntity report, String operationRequestId,
                                      BigDecimal correctionQuantity, Long reverseRequestId,
                                      String evidenceFingerprint) {
        List<MesWarehouseInoutEntity> outputList = inoutService.list(
                new LambdaQueryWrapper<MesWarehouseInoutEntity>()
                        .eq(MesWarehouseInoutEntity::getBillId, report.getId())
                        .eq(MesWarehouseInoutEntity::getResourceNo, report.getRequestId())
                        .eq(MesWarehouseInoutEntity::getBizType, WarehouseInBizTypeEnum.PROCESS_PRODUCT_IN.getCode())
                        .eq(MesWarehouseInoutEntity::getOperateType, InOutEnum.IN.getCode()));
        if (outputList.isEmpty()) {
            throw new CheckedException("原报工缺少产出库存流水，不能自动冲销");
        }
        if (outputList.size() != 1 || outputList.get(0).getOperateNum() == null
                || outputList.get(0).getOperateNum().compareTo(report.getReportNum()) != 0) {
            throw new CheckedException("原报工产出流水不是一笔等额精确来源，禁止按比例猜测部分纠正");
        }
        MesWarehouseInoutEntity output = outputList.get(0);
        BigDecimal correctedBefore = correctedFor(report.getId(), "OUTPUT_FLOW", output.getId());
        BigDecimal remainingBefore = output.getOperateNum().subtract(correctedBefore);
        if (remainingBefore.compareTo(correctionQuantity) < 0) {
            throw new CheckedException("原报工剩余产出库存来源小于本次纠正数量");
        }
        MesWarehouseLiveStockInoutDTO reverseOut = new MesWarehouseLiveStockInoutDTO();
        BeanUtil.copyProperties(output, reverseOut);
        reverseOut.setBillId(report.getId());
        reverseOut.setSourceReportId(report.getId());
        reverseOut.setBillNo(report.getArrangeCode());
        reverseOut.setResourceNo(operationRequestId);
        reverseOut.setNum(correctionQuantity);
        reverseOut.setBizType(correctionQuantity.compareTo(effectiveQuantity(report)) == 0
                ? WarehouseInBizTypeEnum.REPORT_REVERSE_PRODUCT_OUT.getCode()
                : WarehouseInBizTypeEnum.REPORT_CORRECTION_PRODUCT_OUT.getCode());
        reverseOut.setAllowQualityBlocked(Boolean.TRUE);
        liveStockService.outStock(reverseOut);
        saveCorrectionLine(reverseRequestId, operationRequestId, report, "OUTPUT_FLOW", output.getId(),
                Objects.toString(output.getSourceStockId(), ""), output.getOperateNum(), correctedBefore,
                correctionQuantity, evidenceFingerprint);
    }

    private void reverseMaterialConsumption(MesTaskReportEntity report, String operationRequestId,
                                            BigDecimal correctionQuantity, Long reverseRequestId,
                                            String evidenceFingerprint) {
        List<MesWarehouseInoutEntity> backFlushList = inoutService.list(
                new LambdaQueryWrapper<MesWarehouseInoutEntity>()
                        .eq(MesWarehouseInoutEntity::getBillId, report.getId())
                        .eq(MesWarehouseInoutEntity::getResourceNo, report.getRequestId())
                        .eq(MesWarehouseInoutEntity::getBizType, WarehouseInBizTypeEnum.BACK_FLUSH_OUT.getCode())
                        .eq(MesWarehouseInoutEntity::getOperateType, InOutEnum.OUT.getCode()));
        for (MesWarehouseInoutEntity consumed : backFlushList) {
            BigDecimal correctedBefore = correctedFor(report.getId(), "MATERIAL_FLOW", consumed.getId());
            BigDecimal restoreQuantity = proportionalIncrement(consumed.getOperateNum(), report,
                    correctionQuantity, correctedBefore);
            if (restoreQuantity.signum() == 0) continue;
            MesWarehouseLiveStockInoutDTO restore = new MesWarehouseLiveStockInoutDTO();
            BeanUtil.copyProperties(consumed, restore);
            restore.setBillId(report.getId());
            restore.setBillNo(report.getArrangeCode());
            restore.setResourceNo(operationRequestId);
            restore.setNum(restoreQuantity);
            restore.setBizType(correctionQuantity.compareTo(effectiveQuantity(report)) == 0
                    ? WarehouseInBizTypeEnum.REPORT_REVERSE_MATERIAL_IN.getCode()
                    : WarehouseInBizTypeEnum.REPORT_CORRECTION_MATERIAL_IN.getCode());
            liveStockService.inStock(restore);
            saveCorrectionLine(reverseRequestId, operationRequestId, report, "MATERIAL_FLOW", consumed.getId(),
                    StrUtil.blankToDefault(consumed.getMatBatch(), consumed.getMatCode()),
                    consumed.getOperateNum(), correctedBefore, restoreQuantity, evidenceFingerprint);
        }
        reverseFeedAllocations(report, operationRequestId, correctionQuantity, reverseRequestId,
                evidenceFingerprint);
    }

    private void reverseFeedAllocations(MesTaskReportEntity report, String operationRequestId,
                                        BigDecimal correctionQuantity, Long reverseRequestId,
                                        String evidenceFingerprint) {
        List<MesReportMaterialConsumeEntity> consumeList = consumeService.list(
                new LambdaQueryWrapper<MesReportMaterialConsumeEntity>()
                        .eq(MesReportMaterialConsumeEntity::getReportId, report.getId())
                        .eq(MesReportMaterialConsumeEntity::getStatus, ReportStatusEnum.EFFECTIVE.name()));
        for (MesReportMaterialConsumeEntity consume : consumeList) {
            BigDecimal correctedBefore = correctedFor(report.getId(), "MATERIAL_CONSUME", consume.getId());
            BigDecimal restoreQuantity = proportionalIncrement(consume.getConsumeNum(), report,
                    correctionQuantity, correctedBefore);
            if (restoreQuantity.signum() == 0) continue;
            MesWarehouseFeedEntity feed = feedService.getById(consume.getFeedId());
            if (feed == null || feed.getUsedNum() == null
                    || feed.getUsedNum().compareTo(restoreQuantity) < 0) {
                throw new CheckedException("原投料批次的已耗数量不足，不能自动冲销");
            }
            BigDecimal before = feed.getUsedNum();
            BigDecimal after = before.subtract(restoreQuantity);
            boolean feedUpdated = feedService.update(new LambdaUpdateWrapper<MesWarehouseFeedEntity>()
                    .eq(MesWarehouseFeedEntity::getId, feed.getId())
                    .eq(MesWarehouseFeedEntity::getUsedNum, before)
                    .set(MesWarehouseFeedEntity::getUsedNum, after));
            if (!feedUpdated) {
                throw new CheckedException("投料消耗已被其他业务更新，请刷新后重试");
            }
            boolean finalRestore = correctedBefore.add(restoreQuantity).compareTo(consume.getConsumeNum()) == 0;
            if (finalRestore) {
                boolean consumeUpdated = consumeService.update(new LambdaUpdateWrapper<MesReportMaterialConsumeEntity>()
                        .eq(MesReportMaterialConsumeEntity::getId, consume.getId())
                        .eq(MesReportMaterialConsumeEntity::getStatus, ReportStatusEnum.EFFECTIVE.name())
                        .set(MesReportMaterialConsumeEntity::getStatus, ReportStatusEnum.REVERSED.name())
                        .set(MesReportMaterialConsumeEntity::getReverseRequestId, operationRequestId)
                        .set(MesReportMaterialConsumeEntity::getReverseTime, LocalDateTime.now()));
                if (!consumeUpdated) {
                    throw new CheckedException("投料消耗分配已被其他业务冲销，请刷新后重试");
                }
            }
            saveCorrectionLine(reverseRequestId, operationRequestId, report, "MATERIAL_CONSUME", consume.getId(),
                    StrUtil.blankToDefault(consume.getMatBarcode(), consume.getMatBatch()),
                    consume.getConsumeNum(), correctedBefore, restoreQuantity, evidenceFingerprint);
        }
    }

    /** 在原箱内容行上扣减，保留初封数量，通过纠正分配行记录本次扣减及剩余量。 */
    private void reverseUnsealedPackaging(MesTaskReportEntity report, List<MesWrapRecordEntity> records,
                                          String operationRequestId, BigDecimal correctionQuantity,
                                          Long reverseRequestId, String evidenceFingerprint) {
        if (records.isEmpty()) {
            return;
        }
        BigDecimal remaining = correctionQuantity;
        for (MesWrapRecordEntity record : records) {
            if (remaining.signum() == 0 || MesWrapRecordStatusEnum.REVERSED.name().equals(record.getPackingStatus())) {
                continue;
            }
            BigDecimal correctedBefore = correctedFor(report.getId(), "PACKAGE_CONTENT", record.getId());
            BigDecimal sourceRemaining = record.getQuantity().subtract(correctedBefore);
            boolean staged = MesWrapRecordStatusEnum.STAGED.name().equals(record.getPackingStatus());
            BigDecimal storedAvailable = Objects.requireNonNullElse(record.getAvailableQuantity(), BigDecimal.ZERO);
            BigDecimal current = staged ? sourceRemaining : storedAvailable;
            BigDecimal take = remaining.min(sourceRemaining).min(current);
            if (take.signum() <= 0) {
                continue;
            }
            BigDecimal after = current.subtract(take);
            boolean updated = wrapRecordService.update(new LambdaUpdateWrapper<MesWrapRecordEntity>()
                    .eq(MesWrapRecordEntity::getId, record.getId())
                    .eq(MesWrapRecordEntity::getTenantId, report.getTenantId())
                    .eq(MesWrapRecordEntity::getFactoryCode, report.getFactoryCode())
                    .eq(MesWrapRecordEntity::getReportId, report.getId())
                    .eq(MesWrapRecordEntity::getPackingStatus, record.getPackingStatus())
                    .eq(MesWrapRecordEntity::getAvailableQuantity, storedAvailable)
                    .set(MesWrapRecordEntity::getAvailableQuantity, after)
                    .set(MesWrapRecordEntity::getPackingStatus, after.signum() == 0
                            ? MesWrapRecordStatusEnum.REVERSED.name() : MesWrapRecordStatusEnum.PARTIAL.name())
                    .set(MesWrapRecordEntity::getOperationRequestId, operationRequestId));
            if (!updated) {
                throw new CheckedException("包装内容已被其他业务更新，请刷新后重试");
            }
            saveCorrectionLine(reverseRequestId, operationRequestId, report, "PACKAGE_CONTENT", record.getId(),
                    record.getWrapCode(), record.getQuantity(), correctedBefore, take, evidenceFingerprint);
            remaining = remaining.subtract(take);
        }
        if (remaining.signum() > 0) {
            throw new CheckedException("可纠正包装内容小于本次纠正数量，请先完成拆箱或撤回重组");
        }
    }

    private void decreaseBusinessQuantities(MesTaskReportEntity report, MesTaskArrangeEntity arrange,
                                            MesProduceTaskEntity produceTask, MesTaskEntity task,
                                            BigDecimal reportNum) {
        BigDecimal arrangeBefore = requireEnough(arrange.getCompleteNum(), reportNum, "派工完成数量");
        // 当前反向入口只接受NOT_REQUIRED报工，因此本次应释放的质量可用量就是本次纠正量。
        // 不能直接使用原报工的qualityReleasedNum，否则部分纠正会一次性扣掉全部原报工数量。
        BigDecimal releasedNum = reportNum;
        BigDecimal arrangeQualityBefore = requireEnough(
                Objects.requireNonNullElse(arrange.getQualityReleasedNum(), arrangeBefore),
                releasedNum, "派工质量放行数量");
        LambdaUpdateWrapper<MesTaskArrangeEntity> arrangeWrapper = new LambdaUpdateWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getId, arrange.getId())
                .eq(MesTaskArrangeEntity::getCompleteNum, arrangeBefore)
                .set(MesTaskArrangeEntity::getCompleteNum, arrangeBefore.subtract(reportNum))
                .set(MesTaskArrangeEntity::getQualityReleasedNum, arrangeQualityBefore.subtract(releasedNum));
        if (MesProduceTaskStatusEnum.FINISH.name().equals(arrange.getStatus())) {
            // 审批只恢复可执行资格；本人沿原开工入口重新核门禁并记录新RUN。
            arrangeWrapper.set(MesTaskArrangeEntity::getStatus, MesProduceTaskStatusEnum.PAUSE.name())
                    .set(MesTaskArrangeEntity::getFinishTime, null)
                    .set(MesTaskArrangeEntity::getFinishHour, null);
        }
        boolean arrangeUpdated = arrangeService.update(arrangeWrapper);
        if (!arrangeUpdated) {
            throw new CheckedException("派工完成数量已被其他业务更新，请刷新后重试");
        }

        BigDecimal produceBefore = requireEnough(produceTask.getCompleteNum(), reportNum, "工序完成数量");
        BigDecimal produceAfter = produceBefore.subtract(reportNum);
        BigDecimal qualityReleasedBefore = requireEnough(
                Objects.requireNonNullElse(produceTask.getQualityReleasedNum(), produceBefore),
                releasedNum, "工序质量放行数量");
        BigDecimal qualityReleasedAfter = qualityReleasedBefore.subtract(releasedNum);
        LambdaUpdateWrapper<MesProduceTaskEntity> produceWrapper = new LambdaUpdateWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getId, produceTask.getId())
                .eq(MesProduceTaskEntity::getCompleteNum, produceBefore)
                .set(MesProduceTaskEntity::getCompleteNum, produceAfter)
                .set(MesProduceTaskEntity::getQualityReleasedNum, qualityReleasedAfter);
        boolean revokeTransferRelease = !Boolean.TRUE.equals(produceTask.getOverlapEnabled())
                || produceTask.getTransferBatchQuantity() == null
                || qualityReleasedAfter.compareTo(produceTask.getTransferBatchQuantity()) < 0;
        if (revokeTransferRelease) {
            produceWrapper.set(MesProduceTaskEntity::getTransferReleasedTime, null);
        }
        if (MesProduceTaskStatusEnum.FINISH.name().equals(produceTask.getStatus())) {
            produceWrapper.set(MesProduceTaskEntity::getStatus, MesProduceTaskStatusEnum.PRODUCT.name())
                    .set(MesProduceTaskEntity::getFinishTime, null);
        }
        boolean produceUpdated = produceTaskService.update(produceWrapper);
        if (!produceUpdated) {
            throw new CheckedException("工序完成数量已被其他业务更新，请刷新后重试");
        }

        if (Boolean.TRUE.equals(arrange.getLastRoute())) {
            BigDecimal stockBefore = requireEnough(task.getUnInStockNum(), releasedNum, "工单待入库数量");
            // 工单编码可在不同工厂重复，末序完成量只按已锁定的原工单组织汇总。
            BigDecimal completedAfter = produceTaskService.list(new LambdaQueryWrapper<MesProduceTaskEntity>()
                            .eq(MesProduceTaskEntity::getTenantId, task.getTenantId())
                            .eq(MesProduceTaskEntity::getFactoryCode, task.getFactoryCode())
                            .eq(MesProduceTaskEntity::getTaskCode, task.getCode())
                            .eq(MesProduceTaskEntity::getLastRoute, Boolean.TRUE))
                    .stream().map(item -> Objects.requireNonNullElse(item.getCompleteNum(), BigDecimal.ZERO))
                    .min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            LambdaUpdateWrapper<MesTaskEntity> taskWrapper = new LambdaUpdateWrapper<MesTaskEntity>()
                    .eq(MesTaskEntity::getId, task.getId())
                    .eq(MesTaskEntity::getUnInStockNum, stockBefore)
                    .set(MesTaskEntity::getUnInStockNum, stockBefore.subtract(releasedNum))
                    .set(MesTaskEntity::getCompleteNum, completedAfter);
            if (MesTaskStatusEnum.FINISH.name().equals(task.getStatus())) {
                taskWrapper.set(MesTaskEntity::getStatus, MesTaskStatusEnum.PRODUCT.name())
                        .set(MesTaskEntity::getFinishTime, null)
                        .set(MesTaskEntity::getCompletionVersion,
                                Objects.requireNonNullElse(task.getCompletionVersion(), 0) + 1);
            }
            boolean taskUpdated = taskService.update(taskWrapper);
            if (!taskUpdated) {
                throw new CheckedException("工单待入库数量已被其他业务更新，请刷新后重试");
            }
        }
    }

    private BigDecimal requireEnough(BigDecimal current, BigDecimal reverseNum, String fieldName) {
        if (current == null || current.compareTo(reverseNum) < 0) {
            throw new CheckedException(fieldName + "小于冲销数量，不能自动冲销");
        }
        return current;
    }

    private void markReversed(MesTaskReportEntity report, String reason, String reverseRequestId,
                              BigDecimal reversedQuantity) {
        MomUser user = SecurityUtils.getUser();
        LocalDateTime now = LocalDateTime.now();
        boolean updated = reportService.update(new LambdaUpdateWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getId, report.getId())
                .eq(MesTaskReportEntity::getStatus, ReportStatusEnum.EFFECTIVE.name())
                .set(MesTaskReportEntity::getStatus, ReportStatusEnum.REVERSED.name())
                .set(MesTaskReportEntity::getReverseRequestId, reverseRequestId)
                .set(MesTaskReportEntity::getReverseReason, reason)
                .set(MesTaskReportEntity::getReverseTime, now)
                .set(MesTaskReportEntity::getReverseWorkerCode, user.getUsername())
                .set(MesTaskReportEntity::getReverseWorkerName, user.getName())
                .set(MesTaskReportEntity::getEffectiveReportNum, BigDecimal.ZERO)
                .set(MesTaskReportEntity::getCorrectedReportNum, report.getReportNum())
                .set(MesTaskReportEntity::getLastCorrectionTime, now));
        if (!updated) {
            throw new CheckedException("报工状态已被其他业务更新，请刷新后重试");
        }

        boolean originalRecordUpdated = recordService.update(new LambdaUpdateWrapper<MesTaskReportRecordEntity>()
                .eq(MesTaskReportRecordEntity::getReportId, report.getId())
                .eq(MesTaskReportRecordEntity::getType, ReportRecordTypeEnum.REPORT.name())
                .eq(MesTaskReportRecordEntity::getStatus, ReportStatusEnum.EFFECTIVE.name())
                .set(MesTaskReportRecordEntity::getStatus, ReportStatusEnum.REVERSED.name())
                .set(MesTaskReportRecordEntity::getReverseReason, reason)
                .set(MesTaskReportRecordEntity::getReverseTime, now)
                .set(MesTaskReportRecordEntity::getReverseWorkerCode, user.getUsername())
                .set(MesTaskReportRecordEntity::getReverseWorkerName, user.getName())
                .set(MesTaskReportRecordEntity::getEffectiveReportNum, BigDecimal.ZERO)
                .set(MesTaskReportRecordEntity::getCorrectedReportNum, report.getReportNum())
                .set(MesTaskReportRecordEntity::getLastCorrectionTime, now));
        if (!originalRecordUpdated) {
            throw new CheckedException("原报工操作流水状态异常，不能完成冲销");
        }

        MesTaskReportRecordEntity record = new MesTaskReportRecordEntity();
        BeanUtils.copyProperties(report, record, "id");
        record.setReportId(report.getId());
        record.setType(ReportRecordTypeEnum.REVERSE.name());
        record.setReportNum(reversedQuantity);
        record.setEffectiveReportNum(BigDecimal.ZERO);
        record.setCorrectedReportNum(report.getReportNum());
        record.setLastCorrectionTime(now);
        record.setStatus(ReportStatusEnum.REVERSED.name());
        record.setReverseReason(reason);
        record.setReverseTime(now);
        record.setReverseWorkerCode(user.getUsername());
        record.setReverseWorkerName(user.getName());
        record.setReportTime(now);
        record.setWorkerCode(user.getUsername());
        record.setWorkerName(user.getName());
        record.setContent(user.getName() + "于" + DateUtil.format(now, "yyyy-MM-dd HH:mm:ss")
                + "冲销剩余有效报工，数量：" + reversedQuantity + "，原因：" + reason);
        if (!recordService.save(record)) {
            throw new CheckedException("完整冲销履历保存失败，反向库存/数量及节点补偿全部回滚");
        }
    }

    private void markPartiallyCorrected(MesTaskReportEntity report, String reason, String operationRequestId,
                                        BigDecimal correctionQuantity, Long reverseRequestId,
                                        String evidenceFingerprint) {
        MomUser user = SecurityUtils.getUser();
        LocalDateTime now = LocalDateTime.now();
        BigDecimal before = effectiveQuantity(report);
        BigDecimal after = before.subtract(correctionQuantity);
        BigDecimal corrected = Objects.requireNonNullElse(report.getCorrectedReportNum(), BigDecimal.ZERO)
                .add(correctionQuantity);
        boolean updated = reportService.update(new LambdaUpdateWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getId, report.getId())
                .eq(MesTaskReportEntity::getStatus, ReportStatusEnum.EFFECTIVE.name())
                .eq(MesTaskReportEntity::getEffectiveReportNum, before)
                .set(MesTaskReportEntity::getEffectiveReportNum, after)
                .set(MesTaskReportEntity::getCorrectedReportNum, corrected)
                .set(MesTaskReportEntity::getLastCorrectionTime, now));
        if (!updated) throw new CheckedException("报工有效数量已被其他业务更新，请刷新后重试");

        boolean originalRecordUpdated = recordService.update(new LambdaUpdateWrapper<MesTaskReportRecordEntity>()
                .eq(MesTaskReportRecordEntity::getReportId, report.getId())
                .eq(MesTaskReportRecordEntity::getType, ReportRecordTypeEnum.REPORT.name())
                .eq(MesTaskReportRecordEntity::getStatus, ReportStatusEnum.EFFECTIVE.name())
                .set(MesTaskReportRecordEntity::getEffectiveReportNum, after)
                .set(MesTaskReportRecordEntity::getCorrectedReportNum, corrected)
                .set(MesTaskReportRecordEntity::getLastCorrectionTime, now));
        if (!originalRecordUpdated) {
            throw new CheckedException("原报工操作流水状态异常，不能完成部分纠正");
        }

        MesTaskReportRecordEntity record = new MesTaskReportRecordEntity();
        BeanUtils.copyProperties(report, record, "id");
        record.setReportId(report.getId());
        record.setType(ReportRecordTypeEnum.QUANTITY_CORRECTION.name());
        record.setStatus(ReportStatusEnum.EFFECTIVE.name());
        record.setReportNum(correctionQuantity);
        record.setEffectiveReportNum(after);
        record.setCorrectedReportNum(corrected);
        record.setLastCorrectionTime(now);
        record.setReverseReason(reason);
        record.setReverseTime(now);
        record.setReverseWorkerCode(user.getUsername());
        record.setReverseWorkerName(user.getName());
        record.setReportTime(now);
        record.setWorkerCode(user.getUsername());
        record.setWorkerName(user.getName());
        record.setContent(user.getName() + "于" + DateUtil.format(now, "yyyy-MM-dd HH:mm:ss")
                + "完成聚合报工部分纠正，原有效数量：" + before + "，本次纠正：" + correctionQuantity
                + "，剩余有效：" + after + "，申请ID：" + Objects.toString(reverseRequestId, "-")
                + "，证据：" + StrUtil.blankToDefault(evidenceFingerprint, "-") + "，原因：" + reason);
        if (!recordService.save(record)) {
            throw new CheckedException("部分纠正履历保存失败，反向库存及数量全部回滚");
        }
    }

    /**
     * 末工序冲销后恢复流转卡，最近工序仅作展示；再次过站仍以有效报工DAG事实为准。
     */
    private void restoreTravelerAfterReverse(MesTaskReportEntity report, MesTaskArrangeEntity reversedArrange) {
        ReportModeEnum mode = StrUtil.isNotBlank(report.getReportMode())
                ? ReportModeEnum.fromCode(report.getReportMode())
                : ReportModeEnum.fromCode(reversedArrange.getRouteType());
        if (mode != ReportModeEnum.TRANSFER_CARD || StrUtil.isBlank(report.getTransferCode())) {
            return;
        }
        MesTravelerEntity traveler = travelerMapper.selectOne(new LambdaQueryWrapper<MesTravelerEntity>()
                .eq(MesTravelerEntity::getTravelerCode, report.getTransferCode())
                .eq(MesTravelerEntity::getTaskCode, report.getTaskCode())
                .last("FOR UPDATE"));
        if (traveler == null) {
            return;
        }
        MesTaskReportEntity latest = reportService.getOne(new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getTaskCode, report.getTaskCode())
                .eq(MesTaskReportEntity::getTransferCode, report.getTransferCode())
                .eq(MesTaskReportEntity::getStatus, ReportStatusEnum.EFFECTIVE.name())
                .orderByDesc(MesTaskReportEntity::getReportTime)
                .orderByDesc(MesTaskReportEntity::getId)
                .last("LIMIT 1"), false);
        MesTaskArrangeEntity latestArrange = latest == null ? null : arrangeService.getOne(
                new LambdaQueryWrapper<MesTaskArrangeEntity>().eq(MesTaskArrangeEntity::getCode, latest.getArrangeCode()));
        traveler.setStatus("ACTIVE");
        traveler.setCompleteTime(null);
        traveler.setLatestRouteCode(latestArrange == null ? traveler.getFirstRouteCode() : latestArrange.getRouteCode());
        traveler.setLatestRouteOrder(latestArrange == null ? null : latestArrange.getRouteOrder());
        if (travelerMapper.updateById(traveler) != 1) {
            throw new CheckedException("流转卡状态已被其他业务更新，请刷新后重试");
        }
    }
}
