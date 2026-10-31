package com.kitex.mom.mes.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.agent.dto.MesReportQuantityConsistencyDTO;
import com.kitex.mom.mes.dto.MesReportSourceAssignmentDTO;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.entity.MesTaskInWmsReceiptEntity;
import com.kitex.mom.mes.entity.MesTaskInWmsReceiptDetailEntity;
import com.kitex.mom.mes.mapper.MesTaskInWmsReceiptMapper;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import com.kitex.mom.mes.mapper.MesTaskReportMapper;
import com.kitex.mom.mes.service.MesInboundAllocationService;
import com.kitex.mom.mes.service.MesReportSourceAssignmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 在独立只读重复读事务中取证，来源缺证拒绝只回滚本取证，不污染外层统计。来源归属、入库红冲净额复用原业务服务，
 * 不生成核定、不补历史、不调用WMS写接口，也不将各工序产量加成成品量。
 */
@Service
@RequiredArgsConstructor
public class MesReportQuantityEvidenceReader {

    public static final String VERSION = "MES_REPORT_QUANTITY_V1";

    private final MesTaskMapper tasks;
    private final MesTaskReportMapper reports;
    private final MesTaskInWmsReceiptMapper receipts;
    private final MesInboundAllocationService allocations;
    private final MesReportSourceAssignmentService sources;

    /**
     * 对精确工单的全部报工与MES入库申请进行只读核对。
     *
     * <p>完整范围超限由调用方保留为缺证；最多50条明细仅用于展示，不能决定整体验证结果。</p>
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.REPEATABLE_READ)
    public MesReportQuantityConsistencyDTO.Evidence read(String factory, String taskCode) {
        var taskRows = tasks.selectList(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getFactoryCode, factory).eq(MesTaskEntity::getCode, taskCode).last("LIMIT 2"));
        require(taskRows.size() == 1, "当前工厂未找到唯一生产工单，不能核对数量来源");
        MesTaskEntity task = taskRows.get(0);
        var rows = reports.selectList(new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getFactoryCode, factory).eq(MesTaskReportEntity::getTaskCode, taskCode)
                .orderByAsc(MesTaskReportEntity::getId).last("LIMIT 5001"));
        require(rows.size() <= 5000, "当前工单超过5000条报工，未截断核定数量");
        var view = new MesReportQuantityConsistencyDTO.Evidence().setVersion(VERSION).setFactoryCode(factory)
                .setTaskId(task.getId().toString()).setTaskCode(taskCode).setReportCount(rows.size());
        List<String> gaps = new ArrayList<>();
        MesReportSourceAssignmentDTO.Preview source = sources.taskEvidence(task.getId());
        view.setSourceFingerprint(source.getFingerprint());
        require(Objects.equals(source.getTaskCode(), taskCode) && Objects.equals(source.getTaskId(), task.getId().toString())
                        && Objects.equals(source.getFactoryCode(), factory)
                        && Objects.equals(source.getTenantId(), String.valueOf(TenantContextHolder.getTenantId())),
                "原报工来源核定返回了其他业务范围");
        List<MesTaskInWmsReceiptDetailEntity> net = allocations.activeByTask(taskCode, factory, false);
        Map<Long, MesTaskReportEntity> reportIndex = rows.stream().collect(Collectors.toMap(MesTaskReportEntity::getId, row -> row));
        Map<Long, BigDecimal> allocated = new HashMap<>();
        Set<Long> ambiguousAllocations = new HashSet<>();
        Set<String> terminalReports = source.getReports().stream().map(MesReportSourceAssignmentDTO.Report::getReportId).collect(Collectors.toSet());
        var headers = receipts.selectList(new LambdaQueryWrapper<MesTaskInWmsReceiptEntity>()
                .eq(MesTaskInWmsReceiptEntity::getFactoryCode, factory).eq(MesTaskInWmsReceiptEntity::getMesTaskId, task.getId())
                .orderByAsc(MesTaskInWmsReceiptEntity::getId).last("LIMIT 10001"));
        require(headers.size() <= 10000, "入库申请回执超过完整取证上限");
        Map<Long, MesTaskInWmsReceiptEntity> headerIndex = headers.stream().collect(Collectors.toMap(MesTaskInWmsReceiptEntity::getId, row -> row));
        // 原入库净来源必须精确落到同工单的末序报工和原申请回执。
        for (var line : net) {
            var report = reportIndex.get(line.getReportId());
            var header = headerIndex.get(line.getReceiptId());
            if (report == null || header == null || !Objects.equals(header.getMesTaskCode(), taskCode)
                    || !Objects.equals(report.getTaskCode(), line.getTaskCode())
                    || !Objects.equals(report.getRequestId(), line.getReportRequestId())
                    || !Objects.equals(report.getProduceTaskCode(), line.getProduceTaskCode())
                    || !Objects.equals(report.getArrangeCode(), line.getArrangeCode())
                    || !terminalReports.contains(String.valueOf(line.getReportId()))
                    || !positive(line.getAllocatedQuantity())) {
                gaps.add("入库来源" + line.getId() + "未对应原工单、末序报工或回执");
                if (report != null) {
                    ambiguousAllocations.add(report.getId());
                }
                continue;
            }
            allocated.merge(line.getReportId(), line.getAllocatedQuantity(), BigDecimal::add);
        }
        Set<String> terminalUnits = rows.stream().filter(row -> terminalReports.contains(String.valueOf(row.getId()))
                        && !"REVERSED".equals(row.getStatus()) && !"ROUTE_RETURN".equals(row.getExecutionKind()))
                .map(row -> Objects.toString(row.getReportUnit(), "")).collect(Collectors.toSet());
        if (terminalUnits.size() > 1 || terminalUnits.contains("")) gaps.add("末序报工冻结单位缺失或混用，不能跨单位比较工单与入库量");
        List<MesReportQuantityConsistencyDTO.Report> reportViews = new ArrayList<>();
        int arithmeticUnknown = 0, arithmeticBad = 0, qualityUnknown = 0, qualityBad = 0, allocatedUnknown = 0, allocatedBad = 0;
        // 逐报工分别核对纠正守恒、质量分量和来源占用，不跨工序或单位相加。
        for (var report : rows) {
            List<String> issues = new ArrayList<>();
            BigDecimal original = report.getReportNum(), effective = effective(report), corrected = report.getCorrectedReportNum();
            boolean knownStatus = Set.of("EFFECTIVE", "REVERSED").contains(Objects.toString(report.getStatus(), ""));
            boolean knownKind = !StringUtils.hasText(report.getExecutionKind())
                    || Set.of("NORMAL", "ROUTE_RETURN").contains(report.getExecutionKind());
            if (!knownStatus || !knownKind || original == null || corrected == null || effective == null || !StringUtils.hasText(report.getReportUnit())) {
                arithmeticUnknown++; issues.add("原始、纠正、有效数量、状态或冻结单位不完整");
            } else if (original.signum() < 0 || corrected.signum() < 0 || effective.signum() < 0
                    || original.compareTo(effective.add(corrected)) != 0
                    || "REVERSED".equals(report.getStatus()) && report.getEffectiveReportNum() != null && report.getEffectiveReportNum().signum() != 0) {
                arithmeticBad++; issues.add("原始量不等于当前有效量与累计纠正量之和");
            }
            BigDecimal available = null;
            boolean produces = !"ROUTE_RETURN".equals(report.getExecutionKind());
            if ("REVERSED".equals(report.getStatus()) || !produces) {
                available = BigDecimal.ZERO;
            } else if ("NOT_REQUIRED".equals(report.getQualityQuantityStatus())) {
                available = effective;
            } else {
                BigDecimal released = report.getQualityReleasedNum(), pending = report.getQualityPendingNum(), rejected = report.getQualityRejectedNum();
                if (!knownStatus || effective == null || released == null || pending == null || rejected == null
                        || !Set.of("PENDING", "PARTIAL", "RELEASED", "REJECTED").contains(Objects.toString(report.getQualityQuantityStatus(), ""))) {
                    qualityUnknown++; issues.add("质量可用、待判或拒绝数量未完整冻结");
                } else if (released.signum() < 0 || pending.signum() < 0 || rejected.signum() < 0
                        || effective.compareTo(released.add(pending).add(rejected)) != 0) {
                    qualityBad++; issues.add("质量放行、待判与拒绝量不等于当前有效量");
                } else {
                    available = released;
                }
            }
            BigDecimal occupied = allocated.getOrDefault(report.getId(), BigDecimal.ZERO);
            if (ambiguousAllocations.contains(report.getId())) {
                allocatedUnknown++;
                issues.add("存在归属未核清的入库来源，净占用量未知");
                occupied = null;
            } else if (occupied.signum() > 0 && available == null) {
                allocatedUnknown++;
                issues.add("已占用入库来源但当前质量可用量未知");
            } else if (available != null && occupied.compareTo(available) > 0) {
                allocatedBad++;
                issues.add("入库净来源占用超过当前质量可用量");
            }
            reportViews.add(new MesReportQuantityConsistencyDTO.Report().setReportId(report.getId().toString())
                    .setRequestId(report.getRequestId()).setProduceTaskCode(report.getProduceTaskCode()).setReportMode(report.getReportMode())
                    .setUnit(report.getReportUnit()).setStatus(report.getStatus()).setExecutionKind(report.getExecutionKind())
                    .setOriginalQuantity(text(original)).setCorrectedQuantity(text(corrected)).setEffectiveQuantity(text(effective))
                    .setQualityAvailableQuantity(text(available)).setQualityQuantityStatus(report.getQualityQuantityStatus())
                    .setNetInboundAllocatedQuantity(text(occupied)).setIssues(issues));
        }
        List<MesReportQuantityConsistencyDTO.Check> checks = new ArrayList<>();
        checks.add(check("REPORT_CORRECTION", "报工纠正守恒", arithmeticBad, arithmeticUnknown));
        checks.add(check("QUALITY_PARTITION", "质量可用量", qualityBad, qualityUnknown));
        checks.add(new MesReportQuantityConsistencyDTO.Check().setCode("TERMINAL_SOURCE").setName("末序与计划来源")
                .setState(Boolean.TRUE.equals(source.getSourceComplete()) && source.getGaps().isEmpty() ? "PASS" : "UNKNOWN")
                .setSummary(source.getGaps().isEmpty() ? Boolean.TRUE.equals(source.getSourceComplete()) ? "原末序来源已核定" : "原来源尚未核清" : String.join("；", source.getGaps())));
        checks.add(check("INBOUND_AVAILABLE", "入库申请可用量", allocatedBad, allocatedUnknown));
        BigDecimal netTotal = net.stream().map(MesTaskInWmsReceiptDetailEntity::getAllocatedQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        // 每张回执独立对平后再核工单总量，不能以两张回执差额抵消证明来源正确。
        Map<Long, BigDecimal> allocatedByReceipt = new HashMap<>();
        for (var line : net) {
            if (line.getReceiptId() != null) {
                allocatedByReceipt.merge(line.getReceiptId(), line.getAllocatedQuantity(), BigDecimal::add);
            }
        }
        BigDecimal headerNet = BigDecimal.ZERO;
        for (var header : headers) {
            BigDecimal reverse = header.getReversedQuantity();
            if (reverse == null && (header.getReversalStatus() == null || "ACTIVE".equals(header.getReversalStatus()))) {
                reverse = BigDecimal.ZERO;
            }
            if (header.getInStockQuantity() == null || reverse == null || reverse.signum() < 0
                    || header.getInStockQuantity().compareTo(reverse) < 0 || !Objects.equals(header.getMesTaskCode(), taskCode)) {
                gaps.add("入库回执" + header.getId() + "的原数量、红冲量或工单身份不完整");
            } else {
                BigDecimal expected = header.getInStockQuantity().subtract(reverse);
                headerNet = headerNet.add(expected);
                if (expected.compareTo(allocatedByReceipt.getOrDefault(header.getId(), BigDecimal.ZERO)) != 0) {
                    gaps.add("入库回执" + header.getId() + "与其原报工净来源不等额，不能由其他回执差额抵消");
                }
            }
        }
        boolean inboundMatches = gaps.isEmpty() && task.getInStockNum() != null && netTotal.compareTo(task.getInStockNum()) == 0
                && netTotal.compareTo(headerNet) == 0;
        checks.add(new MesReportQuantityConsistencyDTO.Check().setCode("INBOUND_SOURCE").setName("入库申请净来源")
                .setState(inboundMatches ? "PASS" : "UNKNOWN")
                .setSummary(inboundMatches ? "逐回执及工单总量均与红冲后净来源等额；不代表仓库实收" : "工单、入库申请回执与净来源未核平，历史缺来源不能补造"));
        if (rows.isEmpty()) {
            gaps.add("尚无报工记录，不能证明数量链已形成");
        }
        if (!gaps.isEmpty()) checks.add(new MesReportQuantityConsistencyDTO.Check().setCode("SOURCE_SCOPE").setName("完整取证范围")
                .setState("UNKNOWN").setSummary(String.join("；", gaps)));
        boolean anomaly = checks.stream().anyMatch(row -> "ANOMALY".equals(row.getState()));
        boolean complete = arithmeticUnknown == 0 && qualityUnknown == 0 && allocatedUnknown == 0
                && checks.stream().noneMatch(row -> "UNKNOWN".equals(row.getState()));
        reportViews.sort(Comparator.comparing((MesReportQuantityConsistencyDTO.Report row) -> row.getIssues().isEmpty()).thenComparing(MesReportQuantityConsistencyDTO.Report::getReportId));
        return view.setStatus(anomaly ? "ANOMALY" : complete ? "CONSISTENT" : "DATA_GAP").setScopeComplete(complete)
                .setObservedAt(LocalDateTime.now())
                .setChecks(checks).setReportCount(rows.size()).setAllocationCount(net.size())
                .setReports(reportViews.stream().limit(50).toList())
                .setAllocations(net.stream().limit(50).map(line -> new MesReportQuantityConsistencyDTO.Allocation()
                        .setSourceDetailId(line.getId().toString()).setReceiptId(Objects.toString(line.getReceiptId(), null))
                        .setReportId(Objects.toString(line.getReportId(), null)).setRequestId(line.getReportRequestId())
                        .setProduceTaskCode(line.getProduceTaskCode()).setNetQuantity(text(line.getAllocatedQuantity()))).toList())
                .setDetailTruncated(rows.size() > 50 || net.size() > 50).setDataGaps(gaps)
                .setEvidenceRefs(List.of("MES:mes_task:" + taskCode, "MES:reportSourceFingerprint:" + Objects.toString(source.getFingerprint(), "UNKNOWN")));
    }

    /** 已证实偏差优先显示，完整性仍由各未知计数单独决定。 */
    private MesReportQuantityConsistencyDTO.Check check(String code, String name, int bad, int unknown) {
        return new MesReportQuantityConsistencyDTO.Check().setCode(code).setName(name)
                .setState(bad > 0 ? "ANOMALY" : unknown > 0 ? "UNKNOWN" : "PASS")
                .setSummary("已确认偏差" + bad + "项，待补证" + unknown + "项");
    }

    /** 与原来源核定一致：部分纠正后缺有效量时禁止退回原始量。 */
    private BigDecimal effective(MesTaskReportEntity report) {
        if ("REVERSED".equals(report.getStatus())) {
            return BigDecimal.ZERO;
        }
        if (report.getEffectiveReportNum() != null) {
            return report.getEffectiveReportNum();
        }
        return report.getCorrectedReportNum() == null || report.getCorrectedReportNum().signum() == 0 ? report.getReportNum() : null;
    }

    private String text(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }
}
