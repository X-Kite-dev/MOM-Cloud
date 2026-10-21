package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesReportSourceAssignmentDTO;
import com.kitex.mom.mes.entity.MesApsBatchAllocationEntity;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesReportSourceAssignmentEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.mapper.MesApsBatchAllocationMapper;
import com.kitex.mom.mes.mapper.MesProduceTaskMapper;
import com.kitex.mom.mes.mapper.MesReportSourceAssignmentMapper;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import com.kitex.mom.mes.mapper.MesTaskReportMapper;
import com.kitex.mom.mes.service.MesReportSourceAssignmentService;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.exception.MesReportSourceRejectedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 末序报工来源核定的完整数量闭环。
 *
 * <p>报工量取唯一末序的有效报工，并与末序计数核对，不把完工结算字段当在制报工。
 * 多来源由实名人员提供实际凭证与份额；反向冲销整份排除，部分纠正使旧核定失效，必须新版本重核。
 * 核定不写原报工、源分配完成字段、库存或PLAN，入库/质量口径始终独立。</p>
 */
@Service
@RequiredArgsConstructor
public class MesReportSourceAssignmentServiceImpl
        extends ServiceImpl<MesReportSourceAssignmentMapper, MesReportSourceAssignmentEntity>
        implements MesReportSourceAssignmentService {
    private final MesActualFactoryAccessService factoryAccess;
    private final MesTaskMapper taskMapper;
    private final MesProduceTaskMapper operationMapper;
    private final MesTaskReportMapper reportMapper;
    private final MesApsBatchAllocationMapper allocationMapper;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MesReportSourceAssignmentDTO.Preview preview(Long reportId) {
        String factory = factoryAccess.require("read");
        var report = report(reportId, factory, false);
        return facts(taskByCode(report.getTaskCode(), factory, false), reportId, factory).view();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MesReportSourceAssignmentDTO.Preview taskEvidence(Long taskId) {
        String factory = factoryAccess.require("read");
        var task = taskMapper.selectOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getId, taskId).eq(MesTaskEntity::getTenantId, tenant())
                .eq(MesTaskEntity::getFactoryCode, factory));
        require(task != null, "当前实际组织未找到工单");
        return facts(task, null, factory).view();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<Long, MesReportSourceAssignmentDTO.Preview> taskEvidenceBatch(List<Long> taskIds) {
        String factory = factoryAccess.require("read");
        require(taskIds != null && taskIds.size() <= 5000 && taskIds.stream().allMatch(Objects::nonNull)
                        && new HashSet<>(taskIds).size() == taskIds.size(), "须提供完整唯一工单范围，最多5000单");
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        var tasks = taskMapper.selectList(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, tenant()).eq(MesTaskEntity::getFactoryCode, factory).in(MesTaskEntity::getId, taskIds));
        require(tasks.size() == taskIds.size(), "批量取证工单必须全属于当前实际组织");
        var codes = tasks.stream().map(MesTaskEntity::getCode).toList();
        require(codes.stream().allMatch(StrUtil::isNotBlank) && new HashSet<>(codes).size() == tasks.size(),
                "工单编码缺失或重复，不能以同编码报工证明多个物理工单");
        var completeCodeScope = taskMapper.selectList(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, tenant()).eq(MesTaskEntity::getFactoryCode, factory)
                .in(MesTaskEntity::getCode, codes).last("LIMIT 5001"));
        require(completeCodeScope.size() == tasks.size(), "实际组织存在重复工单编码，须先核查身份范围");
        var terminals = operationMapper.selectList(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getTenantId, tenant()).eq(MesProduceTaskEntity::getFactoryCode, factory)
                .in(MesProduceTaskEntity::getTaskCode, codes).eq(MesProduceTaskEntity::getLastRoute, true).last("LIMIT 10001"));
        var allocations = allocationMapper.selectList(new LambdaQueryWrapper<MesApsBatchAllocationEntity>()
                .eq(MesApsBatchAllocationEntity::getTenantId, tenant()).eq(MesApsBatchAllocationEntity::getFactoryCode, factory)
                .in(MesApsBatchAllocationEntity::getMesTaskId, taskIds).orderByAsc(MesApsBatchAllocationEntity::getAllocationSeq)
                .orderByAsc(MesApsBatchAllocationEntity::getId).last("LIMIT 10001"));
        var terminalCodes = terminals.stream().map(MesProduceTaskEntity::getCode).toList();
        var reports = terminalCodes.isEmpty() ? List.<MesTaskReportEntity>of() : reportMapper.selectList(new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getTenantId, tenant()).eq(MesTaskReportEntity::getFactoryCode, factory)
                .in(MesTaskReportEntity::getProduceTaskCode, terminalCodes).in(MesTaskReportEntity::getTaskCode, codes)
                .orderByAsc(MesTaskReportEntity::getId).last("LIMIT 100001"));
        var versions = list(ledgerScope(factory).in(MesReportSourceAssignmentEntity::getTaskId, taskIds)
                .orderByDesc(MesReportSourceAssignmentEntity::getAssignmentVersion)
                .orderByDesc(MesReportSourceAssignmentEntity::getId).last("LIMIT 100001"));
        require(terminals.size() <= 10000 && allocations.size() <= 10000 && reports.size() <= 100000 && versions.size() <= 100000,
                "批量末序/来源/报工/核定范围超过完整取证上限，禁止截断");
        var terminalsByTask = terminals.stream().collect(Collectors.groupingBy(MesProduceTaskEntity::getTaskCode));
        var sourcesByTask = allocations.stream().collect(Collectors.groupingBy(MesApsBatchAllocationEntity::getMesTaskId));
        var reportsByTask = reports.stream().collect(Collectors.groupingBy(MesTaskReportEntity::getTaskCode));
        var versionsByTask = versions.stream().collect(Collectors.groupingBy(MesReportSourceAssignmentEntity::getTaskId));
        Map<Long, MesReportSourceAssignmentDTO.Preview> result = new LinkedHashMap<>();
        for (var task : tasks) {
            result.put(task.getId(), facts(task, null, factory, terminalsByTask.getOrDefault(task.getCode(), List.of()),
                    sourcesByTask.getOrDefault(task.getId(), List.of()), reportsByTask.getOrDefault(task.getCode(), List.of()),
                    versionsByTask.getOrDefault(task.getId(), List.of())).view());
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesReportSourceAssignmentEntity assign(MesReportSourceAssignmentDTO.Assign command) {
        String factory = factoryAccess.require("write");
        require(command != null && command.getReportId() != null, "请选择精确报工记录");
        text(command.getRequestId(), 100, "永久核定号");
        text(command.getExpectedFingerprint(), 64, "预览指纹");
        text(command.getReason(), 500, "核定原因");
        text(command.getEvidenceReference(), 500, "真实凭证引用");
        // 链式撤回会持TASK再锁下游REPORT，不能持本REPORT等待TASK；任务锁冲突立即回滚并保留原号重试。
        var target = report(command.getReportId(), factory, true);
        var task = taskByCode(target.getTaskCode(), factory, true);
        var duplicate = byRequestInternal(command.getRequestId().trim(), factory);
        if (duplicate != null) {
            require(Objects.equals(duplicate.getRequestFingerprint(), requestFingerprint(command, normalize(command.getAllocations()), factory)),
                    "原永久核定号已被不同载荷/人员使用");
            return duplicate;
        }
        // 原号冲突在此范围外，不能把已经存在的原账转换成“明确未提交”。
        MesReportSourceAssignmentEntity entity;
        try {
            var chosen = normalize(command.getAllocations());
            entity = prepareAssignment(command, chosen, requestFingerprint(command, chosen, factory), target, task, factory);
        } catch (CheckedException exception) {
            throw new MesReportSourceRejectedException(command, exception.getMessage());
        }
        // 持久化及提交失败一律保持未知，不发回ROLLBACK_CONFIRMED。
        require(save(entity), "来源核定保存失败");
        return entity;
    }

    /** 原号重放同时核对完整规范化命令和实名人员，不只比较报工ID。 */
    private String requestFingerprint(MesReportSourceAssignmentDTO.Assign command,
                                     List<MesReportSourceAssignmentDTO.Allocation> chosen, String factory) {
        return hash(JSONUtil.toJsonStr(Arrays.asList(tenant().toString(), factory,
                command.getReportId().toString(), command.getExpectedFingerprint(), chosen,
                command.getEvidenceReference().trim(), command.getReason().trim(), SecurityUtils.getUser().getUsername())));
    }

    /** 只读领域校验与实体组装，不写库、不调用远程命令。 */
    private MesReportSourceAssignmentEntity prepareAssignment(MesReportSourceAssignmentDTO.Assign command,
            List<MesReportSourceAssignmentDTO.Allocation> chosen, String requestFingerprint,
            MesTaskReportEntity target, MesTaskEntity task, String factory) {
        Facts facts = facts(task, target.getId(), factory);
        require(Boolean.TRUE.equals(facts.view().getEligible())
                        && Objects.equals(facts.view().getFingerprint(), command.getExpectedFingerprint()),
                "当前报工/来源/数量或其他核定已变化，须重采后核清，不覆盖旧事实");
        BigDecimal effective = effective(target);
        ReportModeEnum mode;
        try {
            mode = ReportModeEnum.fromCode(target.getReportMode());
        } catch (IllegalArgumentException ex) {
            throw new CheckedException("原报工缺少合法报工模式，须先核查历史执行凭证，不能猜成数量报工");
        }
        require(!Set.of(ReportModeEnum.ONE_CODE, ReportModeEnum.TRANSFER_CARD, ReportModeEnum.PACKAGE_ONE_CODE).contains(mode)
                        || chosen.size() == 1,
                "单件码、包装单件码和流转卡不得切成多个来源；整份标识保持同一来源");
        BigDecimal sum = chosen.stream().map(row -> quantity(row.getQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
        require(effective != null && effective.signum() > 0 && sum.compareTo(effective) == 0,
                "本次全部来源份额必须严格等于当前有效报工量，不允许缺量或比例猜量");
        Map<String, BigDecimal> capacities = new HashMap<>();
        facts.view().getSources().forEach(row -> capacities.put(row.getAllocationId(), quantity(row.getAllocatedQuantity())));
        Map<String, BigDecimal> assigned = new HashMap<>(facts.assigned());
        // 重新核清以本报工的新版本替换其当前有效贡献，不重复累加旧版本。
        var prior = facts.validByReport().get(target.getId());
        if (prior != null) {
            readAllocations(prior.getAllocationSnapshot()).forEach(row -> assigned.merge(row.getAllocationId(),
                    quantity(row.getQuantity()).negate(), BigDecimal::add));
        }
        for (var row : chosen) {
            require(capacities.containsKey(row.getAllocationId()), "只能选择原物理工单完整APS来源，不允许自造或跨工厂来源");
            BigDecimal after = assigned.getOrDefault(row.getAllocationId(), BigDecimal.ZERO).add(quantity(row.getQuantity()));
            require(after.compareTo(capacities.get(row.getAllocationId())) <= 0, "来源已核定有效量超过原来源分配量");
        }
        int version = facts.latestByReport().containsKey(target.getId())
                ? facts.latestByReport().get(target.getId()).getAssignmentVersion() + 1 : 1;
        return new MesReportSourceAssignmentEntity().setRequestId(command.getRequestId().trim())
                .setRequestFingerprint(requestFingerprint).setTaskId(task.getId()).setTaskCode(task.getCode())
                .setReportId(target.getId()).setAssignmentVersion(version).setEffectiveQuantity(effective)
                .setReportFingerprint(reportFingerprint(target)).setSourceFingerprint(facts.sourceFingerprint())
                .setAllocationSnapshot(JSONUtil.toJsonStr(chosen)).setEvidenceSnapshot(facts.snapshot())
                .setEvidenceReference(command.getEvidenceReference().trim()).setReason(command.getReason().trim()).setFactoryCode(factory);
    }

    @Override
    public List<MesReportSourceAssignmentEntity> history(Long reportId) {
        String factory = factoryAccess.require("read");
        report(reportId, factory, false);
        // 履历展示元数据和精确份额；不把每版累计全工单证据再次重复加载到页面。
        var rows = list(scope(factory).select(MesReportSourceAssignmentEntity.class,
                        field -> !"evidence_snapshot".equals(field.getColumn()))
                .eq(MesReportSourceAssignmentEntity::getReportId, reportId)
                .orderByDesc(MesReportSourceAssignmentEntity::getAssignmentVersion).last("LIMIT 1001"));
        require(rows.size() <= 1000, "核定历史超过1000版，禁止截断后宣称完整");
        return rows;
    }

    @Override
    public MesReportSourceAssignmentEntity byRequest(String requestId) {
        String factory = factoryAccess.require("read");
        text(requestId, 100, "永久核定号");
        return byRequestInternal(requestId, factory);
    }

    private MesReportSourceAssignmentEntity byRequestInternal(String requestId, String factory) {
        return getOne(scope(factory).eq(MesReportSourceAssignmentEntity::getRequestId, requestId));
    }

    /** 完整物理工单有唯一末序且全量报工计数守恒，才可能形成来源完成量。 */
    private Facts facts(MesTaskEntity task, Long targetReportId, String factory) {
        var identityScope = taskMapper.selectList(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, tenant()).eq(MesTaskEntity::getFactoryCode, factory)
                .eq(MesTaskEntity::getCode, task.getCode()).last("LIMIT 2"));
        require(StrUtil.isNotBlank(task.getCode()) && identityScope.size() == 1,
                "实际组织工单编码缺失或重复，须先核查物理身份");
        List<MesProduceTaskEntity> terminals = operationMapper.selectList(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getTenantId, tenant()).eq(MesProduceTaskEntity::getFactoryCode, factory)
                .eq(MesProduceTaskEntity::getTaskCode, task.getCode()).eq(MesProduceTaskEntity::getLastRoute, true));
        var allocations = allocationMapper.selectList(new LambdaQueryWrapper<MesApsBatchAllocationEntity>()
                .eq(MesApsBatchAllocationEntity::getTenantId, tenant()).eq(MesApsBatchAllocationEntity::getFactoryCode, factory)
                .eq(MesApsBatchAllocationEntity::getMesTaskId, task.getId())
                .orderByAsc(MesApsBatchAllocationEntity::getAllocationSeq).orderByAsc(MesApsBatchAllocationEntity::getId).last("LIMIT 1001"));
        require(allocations.size() <= 1000, "来源超过1000行，禁止截断");
        List<MesTaskReportEntity> reports = reportMapper.selectList(new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getTenantId, tenant()).eq(MesTaskReportEntity::getFactoryCode, factory)
                .eq(MesTaskReportEntity::getTaskCode, task.getCode())
                .in(!terminals.isEmpty(), MesTaskReportEntity::getProduceTaskCode, terminals.stream().map(MesProduceTaskEntity::getCode).toList())
                .orderByAsc(MesTaskReportEntity::getId).last("LIMIT 20001"));
        require(reports.size() <= 20000, "末序报工超过20000行，禁止截断");
        var versions = list(ledgerScope(factory).eq(MesReportSourceAssignmentEntity::getTaskId, task.getId())
                .orderByDesc(MesReportSourceAssignmentEntity::getAssignmentVersion)
                .orderByDesc(MesReportSourceAssignmentEntity::getId).last("LIMIT 20001"));
        require(versions.size() <= 20000, "来源核定超过20000版，禁止截断");
        return facts(task, targetReportId, factory, terminals, allocations, reports, versions);
    }

    /** 单条与批量都使用同一计算，不建立第二套来源量公式。 */
    private Facts facts(MesTaskEntity task, Long targetReportId, String factory, List<MesProduceTaskEntity> terminals,
                        List<MesApsBatchAllocationEntity> allocations, List<MesTaskReportEntity> reports,
                        List<MesReportSourceAssignmentEntity> versions) {
        var view = new MesReportSourceAssignmentDTO.Preview().setTenantId(tenant().toString()).setFactoryCode(factory).setTaskId(task.getId().toString())
                .setTaskCode(task.getCode()).setTargetReportId(targetReportId == null ? null : targetReportId.toString())
                .setPlannedQuantity(decimal(task.getNoticeNum())).setSettledQuantity(decimal(task.getCompleteNum()))
                .setEligible(false).setSourceComplete(false);
        if (terminals.size() != 1) {
            view.getGaps().add("必须有唯一完整末序；多末序/拆装输出不能跨工序累计或取首条猜量");
            return emptyFacts(view);
        }
        var terminal = terminals.get(0);
        Map<Long, MesReportSourceAssignmentEntity> latest = new LinkedHashMap<>();
        // 原报工版本是先后依据；跨实例Snowflake时间不替代业务版本顺序。
        versions.forEach(row -> latest.putIfAbsent(row.getReportId(), row));
        List<Object> sourceRows = new ArrayList<>();
        Set<Long> issueIds = new HashSet<>();
        BigDecimal allocated = BigDecimal.ZERO;
        for (var row : allocations) {
            if (!Objects.equals(row.getMesTaskCode(), task.getCode()) || row.getIssueId() == null
                    || StrUtil.isBlank(row.getIssueNo()) || !issueIds.add(row.getIssueId())
                    || row.getAllocatedQuantity() == null || row.getAllocatedQuantity().signum() <= 0) {
                view.getGaps().add("原APS来源身份、唯一性或数量不可核验");
            }
            allocated = allocated.add(row.getAllocatedQuantity() == null ? BigDecimal.ZERO : row.getAllocatedQuantity());
            sourceRows.add(Arrays.asList(row.getId().toString(), Objects.toString(row.getIssueId(), null), row.getIssueNo(),
                    decimal(row.getAllocatedQuantity()), row.getApsBatchFingerprint(), row.getAllocationSeq()));
            view.getSources().add(new MesReportSourceAssignmentDTO.Source().setAllocationId(row.getId().toString())
                    .setIssueNo(row.getIssueNo()).setAllocatedQuantity(decimal(row.getAllocatedQuantity())));
        }
        if (task.getNoticeNum() == null || task.getNoticeNum().signum() <= 0 || !allocations.isEmpty()
                && allocated.compareTo(task.getNoticeNum()) != 0 || StrUtil.isNotBlank(task.getApsBatchNo()) && allocations.isEmpty()) {
            view.getGaps().add("物理工单和APS来源数量不守恒或合批缺来源");
        }
        String sourceFingerprint = hash(JSONUtil.toJsonStr(Arrays.asList(tenant().toString(), factory, task.getId().toString(),
                task.getCode(), task.getMatCode(), task.getApsBatchNo(), decimal(task.getNoticeNum()), sourceRows)));
        // 新基线增加直接计划和末序身份保护，不改已持久化核定账的来源指纹算法。
        view.setSourceFingerprint(sourceFingerprint).setSourceIdentityFingerprint(hash(JSONUtil.toJsonStr(
                Arrays.asList(sourceFingerprint, task.getPlanNo(), terminal.getId(), terminal.getCode(),
                        terminal.getRouteCode(), terminal.getRouteOrder()))));
        Map<String, BigDecimal> assigned = new HashMap<>();
        Set<String> sourceIds = view.getSources().stream()
                .map(MesReportSourceAssignmentDTO.Source::getAllocationId).collect(Collectors.toSet());
        Map<Long, MesReportSourceAssignmentEntity> valid = new HashMap<>();
        BigDecimal live = BigDecimal.ZERO;
        boolean sourceComplete = true;
        boolean targetEligible = false;
        int shareCount = 0;
        for (var report : reports) {
            // 批量查询的两个IN只缩小范围，不能证明工单与唯一末序的精确配对。
            // 错配报工必须进入缺口，不能贴上当前工单身份后再以数量守恒认定其来源。
            if (!Objects.equals(report.getTaskCode(), task.getCode())
                    || !Objects.equals(report.getProduceTaskCode(), terminal.getCode())) {
                view.getGaps().add("报工" + report.getId() + "的工单/唯一末序引用不匹配，禁止计入当前来源");
                sourceComplete = false;
                continue;
            }
            // 原标识退回重过站不重复形成末序新产出，原首轮产出仍是权威数量。
            if ("ROUTE_RETURN".equals(report.getExecutionKind())) {
                continue;
            }
            var current = new MesReportSourceAssignmentDTO.Report().setReportId(report.getId().toString())
                    .setReportTime(report.getReportTime() == null ? null : report.getReportTime().toString())
                    .setRequestId(report.getRequestId()).setReportType(report.getType()).setArrangeCode(report.getArrangeCode())
                    .setReportMode(report.getReportMode()).setReportUnit(report.getReportUnit())
                    .setSourceEventId(report.getSourceEventId())
                    .setSourceEventTime(report.getSourceEventTime() == null ? null : report.getSourceEventTime().toString())
                    .setReportFingerprint(reportFingerprint(report))
                    .setEventFingerprint(hash(JSONUtil.toJsonStr(Arrays.asList(reportFingerprint(report),
                            report.getRequestId(), report.getType(), report.getArrangeCode(), report.getDeviceCode(), report.getSourceEventTime() == null
                                    ? null : report.getSourceEventTime().toString()))))
                    .setOriginalQuantity(decimal(report.getReportNum())).setEffectiveQuantity(decimal(effective(report)))
                    .setStatus(report.getStatus()).setAllocations(List.of());
            view.getReports().add(current);
            if ("REVERSED".equals(report.getStatus())) {
                current.setAssignmentState("REVERSED");
                continue;
            }
            BigDecimal quantity = effective(report);
            if (!"EFFECTIVE".equals(report.getStatus()) || quantity == null || quantity.signum() < 0
                    || StrUtil.isNotBlank(report.getExecutionKind()) && !"NORMAL".equals(report.getExecutionKind())
                    || !Objects.equals(report.getMatCode(), task.getMatCode())
                    || report.getReportNum() == null || quantity.compareTo(report.getReportNum()) > 0) {
                view.getGaps().add("报工" + report.getId() + "状态、产品或当前有效量不可核验");
                current.setAssignmentState("STALE");
                sourceComplete = false;
                continue;
            }
            live = live.add(quantity);
            if (allocations.size() <= 1) {
                current.setAssignmentState("DIRECT");
                if (allocations.size() == 1) assigned.merge(allocations.get(0).getId().toString(), quantity, BigDecimal::add);
            } else {
                var record = latest.get(report.getId());
                if (record == null) {
                    current.setAssignmentState("UNASSIGNED");
                    sourceComplete = false;
                } else {
                    var shares = normalize(readAllocations(record.getAllocationSnapshot()));
                    shareCount += shares.size();
                    require(shareCount <= 100000, "当前来源份额超过100000项完整取证上限，须专项对账，不截断");
                    BigDecimal recorded = shares.stream().map(row -> quantity(row.getQuantity()))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    require(record.getEffectiveQuantity() != null && recorded.compareTo(record.getEffectiveQuantity()) == 0
                                    && shares.stream().allMatch(row -> sourceIds.contains(row.getAllocationId())),
                            "原核定快照份额、来源或数量损坏，须人工对账，不能静默丢弃");
                    current.setAssignmentId(record.getId().toString()).setAllocations(shares);
                    boolean matches = Objects.equals(record.getReportFingerprint(), reportFingerprint(report))
                            && Objects.equals(record.getSourceFingerprint(), sourceFingerprint)
                            && quantity.compareTo(record.getEffectiveQuantity()) == 0;
                    current.setAssignmentState(matches ? "ASSIGNED" : "STALE");
                    sourceComplete &= matches;
                    if (matches) {
                        valid.put(report.getId(), record);
                        current.getAllocations().forEach(row -> assigned.merge(row.getAllocationId(), quantity(row.getQuantity()), BigDecimal::add));
                    }
                }
                if (Objects.equals(targetReportId, report.getId()) && quantity.signum() > 0) targetEligible = true;
            }
        }
        if (terminal.getCompleteNum() == null || live.compareTo(terminal.getCompleteNum()) != 0
                || task.getNoticeNum() != null && live.compareTo(task.getNoticeNum()) > 0
                || "FINISH".equals(task.getStatus()) && (task.getCompleteNum() == null || live.compareTo(task.getCompleteNum()) != 0)) {
            view.getGaps().add("当前有效末序报工与末序计数不守恒，不能拿工单结算量替代或填零");
        }
        for (var source : view.getSources()) {
            BigDecimal total = assigned.getOrDefault(source.getAllocationId(), BigDecimal.ZERO);
            source.setAssignedQuantity(decimal(total));
            if (source.getAllocatedQuantity() == null || total.compareTo(quantity(source.getAllocatedQuantity())) > 0) {
                view.getGaps().add(source.getIssueNo() + "当前有效核定超过原来源分配量");
            }
        }
        // 物理总量守恒不证明实际来源各自受原分配量约束；未核定/失效报工即使报满也仍未知。
        view.setQuantityBasis(!view.getGaps().isEmpty() || !sourceComplete ? "UNVERIFIED"
                        : allocations.size() <= 1 ? "DIRECT" : "EXPLICIT")
                .setLiveReportedQuantity(view.getGaps().isEmpty() ? decimal(live) : null)
                .setSourceComplete(sourceComplete && view.getGaps().isEmpty()).setEligible(targetEligible && view.getGaps().isEmpty());
        if (targetReportId != null) {
            if (allocations.size() <= 1) {
                view.setEligibilityReason("直接或独占来源按完整末序自动归属，无须重复人工核定");
            } else if (!targetEligible) {
                view.setEligibilityReason("仅唯一末序的当前有效正常产出可核定；已冲销、退回重过站、非末序及零量只读核查");
            } else if (!view.getGaps().isEmpty()) {
                view.setEligibilityReason("当前来源/报工计数不守恒，先核对数据，不允许以核定遮盖缺口");
            } else {
                var selected = reports.stream().filter(row -> Objects.equals(row.getId(), targetReportId)).findFirst().orElseThrow();
                try {
                    ReportModeEnum.fromCode(selected.getReportMode());
                } catch (IllegalArgumentException ex) {
                    view.setEligible(false).setEligibilityReason("历史报工未冻结合法模式，不能猜成数量报工；须核查原执行凭证");
                }
            }
        }
        String snapshot = JSONUtil.toJsonStr(Arrays.asList("MES_REPORT_SOURCE_V1", sourceFingerprint, view,
                reports.stream().map(this::reportFingerprint).toList(), latest.values().stream().map(row -> row.getId().toString()).toList()));
        require(snapshot.getBytes(StandardCharsets.UTF_8).length <= 8 * 1024 * 1024,
                "来源证据超过8MiB完整取证上限，须专项对账，不截断证据");
        view.setFingerprint(hash(snapshot));
        return new Facts(view, sourceFingerprint, snapshot, assigned, latest, valid);
    }

    private Facts emptyFacts(MesReportSourceAssignmentDTO.Preview view) {
        view.setFingerprint(hash(JSONUtil.toJsonStr(view)));
        return new Facts(view, null, JSONUtil.toJsonStr(view), Map.of(), Map.of(), Map.of());
    }

    private List<MesReportSourceAssignmentDTO.Allocation> normalize(List<MesReportSourceAssignmentDTO.Allocation> allocations) {
        require(allocations != null && !allocations.isEmpty() && allocations.size() <= 1000, "须明确填写1至1000个来源份额");
        Set<String> ids = new HashSet<>();
        List<MesReportSourceAssignmentDTO.Allocation> normalized = new ArrayList<>();
        for (var row : allocations) {
            require(row != null && row.getAllocationId() != null && row.getAllocationId().matches("[1-9][0-9]{0,18}")
                            && ids.add(row.getAllocationId()) && quantity(row.getQuantity()).signum() > 0,
                    "来源主键须为唯一正整数文本，份额须为明确正数量");
            normalized.add(new MesReportSourceAssignmentDTO.Allocation().setAllocationId(row.getAllocationId())
                    .setQuantity(decimal(quantity(row.getQuantity()))));
        }
        normalized.sort(java.util.Comparator.comparing(MesReportSourceAssignmentDTO.Allocation::getAllocationId));
        return normalized;
    }

    private List<MesReportSourceAssignmentDTO.Allocation> readAllocations(String snapshot) {
        try {
            return JSONUtil.toList(JSONUtil.parseArray(snapshot), MesReportSourceAssignmentDTO.Allocation.class);
        } catch (RuntimeException ex) {
            throw new CheckedException("原不可变来源核定快照不可解析，禁止以空份额替代");
        }
    }

    private String reportFingerprint(MesTaskReportEntity report) {
        String legacySnapshot = JSONUtil.toJsonStr(Arrays.asList(report.getId().toString(), report.getTaskCode(), report.getProduceTaskCode(),
                report.getMatCode(), report.getStatus(), report.getExecutionKind(), report.getRouteReturnId(),
                decimal(report.getReportNum()), decimal(effective(report)), decimal(report.getCorrectedReportNum()),
                report.getReportMode(), report.getReportUnit(), report.getTransferCode(), report.getSubmittedIdentifier(),
                Objects.toString(report.getIdentifierMappingId(), null), report.getIdentifierType(),
                report.getIdentifierResolutionSnapshot(), report.getExtensionEvidenceSnapshot(),
                report.getReportTime() == null ? null : report.getReportTime().toString(), report.getSourceEventId()));
        if (report.getIdentifierBindingId() == null && report.getIdentifierBindingVersion() == null
                && report.getIdentifierNodeAction() == null && report.getIdentifierBindingSnapshot() == null) {
            return hash(legacySnapshot); // 保持旧NONE来源核定算法字节级兼容，不追加四个null使旧审批变STALE。
        }
        require(report.getIdentifierBindingId() != null && report.getIdentifierBindingVersion() != null
                && report.getIdentifierNodeAction() != null && report.getIdentifierBindingSnapshot() != null,
                "动态报工事实不完整，禁止核定来源");
        return hash(JSONUtil.toJsonStr(Arrays.asList("MES_SOURCE_REPORT_BINDING_V2", legacySnapshot,
                report.getIdentifierBindingId().toString(), report.getIdentifierBindingVersion(),
                report.getIdentifierNodeAction(), report.getIdentifierBindingSnapshot())));
    }

    private BigDecimal effective(MesTaskReportEntity report) {
        if ("REVERSED".equals(report.getStatus())) {
            return BigDecimal.ZERO;
        }
        if (report.getEffectiveReportNum() != null) {
            return report.getEffectiveReportNum();
        }
        return report.getCorrectedReportNum() == null || report.getCorrectedReportNum().signum() == 0 ? report.getReportNum() : null;
    }

    private MesTaskReportEntity report(Long id, String factory, boolean lock) {
        var query = new LambdaQueryWrapper<MesTaskReportEntity>().eq(MesTaskReportEntity::getId, id)
                .eq(MesTaskReportEntity::getTenantId, tenant()).eq(MesTaskReportEntity::getFactoryCode, factory);
        if (lock) {
            query.last("FOR UPDATE");
        }
        var record = reportMapper.selectOne(query);
        require(record != null, "当前实际组织未找到原报工");
        return record;
    }

    private MesTaskEntity taskByCode(String code, String factory, boolean lock) {
        var query = new LambdaQueryWrapper<MesTaskEntity>().eq(MesTaskEntity::getCode, code)
                .eq(MesTaskEntity::getTenantId, tenant()).eq(MesTaskEntity::getFactoryCode, factory);
        if (lock) {
            query.last("FOR UPDATE NOWAIT");
        }
        var task = taskMapper.selectOne(query);
        require(task != null, "当前实际组织未找到物理工单");
        return task;
    }

    private LambdaQueryWrapper<MesReportSourceAssignmentEntity> scope(String factory) {
        return new LambdaQueryWrapper<MesReportSourceAssignmentEntity>().eq(MesReportSourceAssignmentEntity::getTenantId, tenant())
                .eq(MesReportSourceAssignmentEntity::getFactoryCode, factory);
    }

    /** 数量读侧只加载计算所需字段，避免全工单累计证据在历史版本间二次膨胀。 */
    private LambdaQueryWrapper<MesReportSourceAssignmentEntity> ledgerScope(String factory) {
        return scope(factory).select(MesReportSourceAssignmentEntity::getId, MesReportSourceAssignmentEntity::getTaskId,
                MesReportSourceAssignmentEntity::getReportId, MesReportSourceAssignmentEntity::getAssignmentVersion,
                MesReportSourceAssignmentEntity::getEffectiveQuantity, MesReportSourceAssignmentEntity::getReportFingerprint,
                MesReportSourceAssignmentEntity::getSourceFingerprint, MesReportSourceAssignmentEntity::getAllocationSnapshot);
    }

    private Long tenant() {
        return TenantContextHolder.getTenantId();
    }

    private BigDecimal quantity(String value) {
        require(value != null && value.matches("[0-9]{1,22}(\\.[0-9]{1,8})?"), "数量须为十进制文本且最多8位小数");
        return new BigDecimal(value);
    }

    private String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private void text(String value, int max, String name) {
        require(StrUtil.isNotBlank(value) && value.length() <= max, name + "必填且不得超过" + max + "位");
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("来源核定指纹生成失败");
        }
    }

    private void require(boolean valid, String message) {
        if (!valid) {
            throw new CheckedException(message);
        }
    }

    private record Facts(MesReportSourceAssignmentDTO.Preview view, String sourceFingerprint, String snapshot,
                         Map<String, BigDecimal> assigned, Map<Long, MesReportSourceAssignmentEntity> latestByReport,
                         Map<Long, MesReportSourceAssignmentEntity> validByReport) {
    }
}
