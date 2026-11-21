package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.support.MdmProcessTimingPolicy;
import com.kitex.mom.mdm.support.MdmRouteNodeTimingPolicy;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.MesArrangeTimingEventService;
import com.kitex.mom.mes.service.MesIdentifierBindingService;
import com.kitex.mom.mes.support.MesReworkTimingPolicy;
import com.kitex.mom.mes.util.MesFrozenExecutionGraph;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 合格/不合格、Web/PAD/PDA/设备/API共用的跨工序报工时间门。
 * 必须在原工单锁内、任何记产和库存副作用之前调用；不访问当前MDM规则。
 * 本组件是业务校验器，不承担实体CRUD，持久化由原标准IService完成。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MesProcessTimingGuard {
    private static final String NODE_EVIDENCE = "MES_PROCESS_TIMING_EVIDENCE_V6";
    private static final String NODE_DEPENDENCY = "MES_TIMING_DEPENDENCY_V4";
    private final MesProduceTaskMapper tasks;
    private final MesTaskMapper orders;
    private final MesTaskReportMapper reports;
    private final MesIdentifierRouteReturnMapper routeReturns;
    private final MesTimingExceptionMapper exceptions;
    private final MesTimingExceptionRecorder exceptionRecorder;
    private final MesArrangeTimingEventService timingEvents;
    private final MesExecutionNodeReader executionNodes;

    private static void addNodeContract(LinkedHashMap<String, Object> body, MesProduceTaskEntity target) {
        body.put("routeExecutionSchemaVersion", target.getRouteExecutionSchemaVersion());
        body.put("targetRouteNodeCode", target.getSourceRouteNodeCode());
        body.put("nodeTimingRulesJson", target.getNodeTimingRulesJson());
    }

    /**
     * 只能由数据库里的原观察选择旧协议，报工DTO没有降级开关。
     */
    private static boolean nodeProtocol(MesProduceTaskEntity target, String originalDependency) {
        boolean node = MesFrozenExecutionGraph.NODE_EXECUTION_V1.equals(target.getRouteExecutionSchemaVersion());
        if (originalDependency == null) return node;
        String version = readEvidence(originalDependency, "原时间依赖正文无效").path("version").asText();
        if (NODE_DEPENDENCY.equals(version)) {
            require(node, "节点时间观察不能用于旧执行合同");
            return true;
        }
        if ("MES_REWORK_TIMING_DEPENDENCY_V1".equals(version)) {
            require(node, "返工跨工单时间观察不能用于旧执行合同");
            return true;
        }
        require(Set.of("MES_TIMING_DEPENDENCY_V1", "MES_TIMING_DEPENDENCY_V2", "MES_TIMING_DEPENDENCY_V3").contains(version),
                "原时间依赖协议不支持，不能自动改用新规则");
        return false;
    }

    private static long ceilingSeconds(LocalDateTime start, LocalDateTime end) {
        Duration duration = Duration.between(start, end);
        return Math.max(0, duration.getSeconds() + (duration.getNano() == 0 ? 0 : 1));
    }

    /**
     * 只解析服务器查询函数返回的原文，不接受客户端提供的轮次或来源声明。
     */
    private static com.fasterxml.jackson.databind.JsonNode readEvidence(String raw, String message) {
        try {
            require(raw != null && !raw.isBlank(), message);
            var parsed = new ObjectMapper().readTree(raw);
            require(parsed != null && parsed.isObject(), message);
            return parsed;
        } catch (CheckedException rejected) {
            throw rejected;
        } catch (Exception rejected) {
            throw new CheckedException(message);
        }
    }

    private static String prefix(MdmProcessTimingPolicy.Rule rule) {
        return "时间规则【" + rule.name() + "】：";
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    /**
     * 校验冻结规则。无规则仍记录接受时刻，供后续有规则的工序引用。
     * 未找到、来源多义、时间未到和已经超时均阻断，不取第一条/最新一条兜底。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Decision validate(MesTaskArrangeEntity arrange, ReportModeEnum mode, String identifier,
                             String executionKind, Integer routeVersion, MesIdentifierBindingService.Prepared binding,
                             MesTimingExceptionFacts.Context context) {
        // 升级前已观察/批准的原号必须沿原依赖协议复核，不能先生成新协议再比较旧原文。
        var permit = context == null || context.requestId() == null ? null
                : exceptions.selectOne(new LambdaQueryWrapper<MesTimingExceptionEntity>()
                .eq(MesTimingExceptionEntity::getTenantId, arrange.getTenantId())
                .eq(MesTimingExceptionEntity::getFactoryCode, arrange.getFactoryCode())
                .eq(MesTimingExceptionEntity::getReportKind, context.reportKind())
                .eq(MesTimingExceptionEntity::getReportRequestId, context.requestId()).last("FOR UPDATE"));
        String contextJson = context == null ? null : MesTimingExceptionFacts.json(context);
        if (permit != null) {
            require(Objects.equals(permit.getContextJson(), contextJson), "超时原请求输入已变化，不能借用原号切换计时协议");
        }
        Decision observed = inspectInternal(arrange, mode, identifier, executionKind, routeVersion, binding,
                permit == null ? null : permit.getDependencyJson());
        if (permit != null) {
            require(Objects.equals(permit.getDependencyJson(), observed.dependencyJson()),
                    "超时原请求的来源已变化，原许可不能用于另一份报工");
        }
        if (observed.lateRules().isEmpty()) return observed;
        require(context != null && context.requestId() != null, "超时报工缺少真实原请求上下文");
        if (permit != null) {
            if (observed.limitUntil() != null && observed.acceptedAt().isAfter(observed.limitUntil())) {
                throw new CheckedException("报工已超过工艺允许的最晚时刻 " + observed.limitUntil()
                        + "，无法延期。报工原号 " + context.requestId());
            }
            require("APPROVED".equals(permit.getStatus()) && permit.getApprovedUntil() != null
                            && !observed.acceptedAt().isAfter(permit.getApprovedUntil())
                            && observed.limitUntil() != null && !permit.getApprovedUntil().isAfter(observed.limitUntil()),
                    "时间规则已超时，当前没有有效延期批准。请在报工超时处理中查询原号 " + context.requestId());
            try {
                var evidence = new ObjectMapper().readTree(observed.evidenceJson());
                var root = (com.fasterxml.jackson.databind.node.ObjectNode) evidence;
                if ("MES_REWORK_TIMING_EVIDENCE_V1".equals(root.path("version").asText())) {
                    root.put("version", "MES_REWORK_TIMING_EVIDENCE_V2");
                } else if (!Set.of("MES_PROCESS_TIMING_EVIDENCE_V4", "MES_PROCESS_TIMING_EVIDENCE_V5", NODE_EVIDENCE)
                        .contains(root.path("version").asText())) {
                    root.put("version", "MES_PROCESS_TIMING_EVIDENCE_V3");
                }
                root.put("exceptionId", permit.getId().toString());
                root.put("exceptionVersion", permit.getVersionNo());
                root.put("exceptionContext", contextJson);
                root.put("exceptionDependency", observed.dependencyJson());
                root.put("approvedUntil", permit.getApprovedUntil().toString());
                return new Decision(observed.acceptedAt(), root.toString(), observed.dependencyJson(),
                        observed.limitUntil(), observed.lateRules());
            } catch (Exception failure) {
                throw new CheckedException("时间例外证据构造失败，未消费许可");
            }
        }
        var observation = new MesTimingExceptionEntity().setFactoryCode(arrange.getFactoryCode())
                .setReportKind(context.reportKind()).setReportRequestId(context.requestId())
                .setActorId(Long.valueOf(context.actorId())).setActorCode(context.actorCode())
                .setTaskCode(arrange.getTaskCode()).setArrangeCode(arrange.getCode())
                .setProduceTaskCode(arrange.getProduceTaskCode()).setReportMode(mode.name())
                .setIdentifier(identifier).setQuantity(new java.math.BigDecimal(context.quantity()))
                .setContextJson(contextJson).setDependencyJson(observed.dependencyJson())
                .setEvidenceJson(observed.evidenceJson()).setObservedAt(observed.acceptedAt())
                .setLimitUntil(observed.limitUntil()).setStatus("DETECTED").setVersionNo(1);
        observation.setTenantId(arrange.getTenantId());
        require(TransactionSynchronizationManager.isSynchronizationActive(), "超时观察必须归属真实报工事务");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_ROLLED_BACK) return;
                try {
                    exceptionRecorder.record(observation);
                } catch (Exception failure) {
                    log.error("超时观察登记失败，原报工未成功；tenant={}, factory={}, request={}",
                            observation.getTenantId(), observation.getFactoryCode(), observation.getReportRequestId(), failure);
                }
            }
        });
        String nextStep = observed.limitUntil() != null && observed.acceptedAt().isBefore(observed.limitUntil())
                ? "可在工艺上限 " + observed.limitUntil() + " 前申请延期"
                : "已到工艺时限，本次无法再申请延期";
        throw new CheckedException("时间规则【" + String.join("、", observed.lateRules())
                + "】已超时，未记产。" + nextStep + "。请在报工超时处理中查询原号 " + context.requestId());
    }

    /**
     * 审批重新取实际来源；不记录新观察、不自动签许可，最短等待等所有身份门仍严格校验。
     */
    public Decision inspect(MesTaskArrangeEntity arrange, ReportModeEnum mode, String identifier, String originalDependency) {
        require(TransactionSynchronizationManager.isActualTransactionActive(), "时间来源复核须在实际事务内");
        require(originalDependency != null && !originalDependency.isBlank(), "审批必须携带原观察依赖正文");
        // 新版由实际批准路线重新解析；旧版仍按原首轮限定，不由审批者指定加工轮次。
        return inspectInternal(arrange, mode, identifier, null, null, null, originalDependency);
    }

    private Decision inspectInternal(MesTaskArrangeEntity arrange, ReportModeEnum mode, String identifier,
                                     String executionKind, Integer routeVersion, MesIdentifierBindingService.Prepared binding,
                                     String originalDependency) {
        Long tenant = TenantContextHolder.getTenantId();
        require(Objects.equals(tenant, arrange.getTenantId()), "报工时间校验租户不一致");
        MesProduceTaskEntity target = actualTask(arrange, arrange.getProduceTaskCode());
        MesFrozenExecutionGraph.assertArrangement(target, arrange);
        boolean nodeRules = nodeProtocol(target, originalDependency);
        var policy = nodeRules ? null : MdmProcessTimingPolicy.parse(target.getTimingRulesJson());
        var nodePolicy = nodeRules ? MdmRouteNodeTimingPolicy.parse(target.getNodeTimingRulesJson()) : null;
        MesFrozenExecutionGraph graph = null;
        if (nodeRules) {
            graph = executionNodes.readGraphs(tenant, arrange.getFactoryCode(), List.of(arrange.getTaskCode()))
                    .get(arrange.getTaskCode());
            graph.key(target);
            graph.current(arrange);
        } else {
            require(Objects.equals(uniqueTask(arrange, arrange.getRouteCode()).getId(), target.getId()),
                    "原时间协议目标主工序不唯一，不能猜测或升级旧许可");
        }
        LocalDateTime accepted = reports.timingClock();
        require(accepted != null, "无法获取报工接受时刻");
        MesTaskEntity order = currentOrder(arrange);
        List<ResolvedRule> rules = new ArrayList<>();
        if (nodeRules) {
            for (var rule : nodePolicy.rules()) {
                MesProduceTaskEntity frozenSource = graph.ancestorNode(target, rule.sourceRouteNodeCode());
                MesProduceTaskEntity source = actualTask(arrange, frozenSource.getCode());
                graph.key(source);
                rules.add(new ResolvedRule(rule.calculation(source.getRouteCode()), rule, source));
            }
        } else {
            for (var rule : policy.rules()) {
                rules.add(new ResolvedRule(rule, rule, uniqueTask(arrange, rule.sourceProcessCode())));
            }
        }
        Decision reworkTiming = reworkTiming(order, target, arrange, mode, identifier, accepted);
        require(reworkTiming == null || rules.isEmpty(),
                "正式返工跨工单时间策略不能夹带普通工单前后序规则，请重新批准返工执行方案");
        if (rules.isEmpty()) return reworkTiming == null
                ? new Decision(accepted, null, null, null, List.of()) : reworkTiming;
        boolean visitRules = nodeRules || MdmProcessTimingPolicy.VERSION.equals(policy.version());
        String visitContext = null;
        if (visitRules) {
            visitContext = reports.timingRouteContext(tenant, arrange.getFactoryCode(), arrange.getTaskCode(),
                    identifier, arrange.getProduceTaskCode());
            var current = readEvidence(visitContext, "批准路线时间上下文缺失");
            require("MES_TIMING_ROUTE_CONTEXT_V1".equals(current.path("version").asText()), "批准路线时间上下文版本错误");
            if (executionKind != null) {
                require(executionKind.equals(current.path("executionKind").asText())
                                && Objects.equals(String.valueOf(routeVersion), current.path("routeVersion").asText()),
                        "当前时间规则加工轮次与实际报工不一致；退回中的标识不能按普通或不良报工执行");
            }
            if (!current.path("loops").isEmpty()) {
                require(rules.stream().allMatch(rule -> rule.calculation().sourceVisit() != null),
                        "该标识有批准退回历史，所有时间规则须由工程明确选择来源轮次，不能自动套用首轮规则");
            }
        } else {
            require(executionKind == null || ("NORMAL".equals(executionKind) && Integer.valueOf(1).equals(routeVersion)),
                    "旧时间规则不支持退回加工轮次，禁止用正常工序时间代替");
        }
        // 新BIND尚未写实例；仅接收上层prepare生成的真实结果，不能拿HTTP隐藏字段查询/伪造占用。
        if (binding != null) {
            require(mode == ReportModeEnum.ONE_CODE && binding.binding() != null && binding.binding().getId() != null
                            && Objects.equals(binding.binding().getTenantId(), tenant)
                            && Objects.equals(binding.binding().getFactoryCode(), arrange.getFactoryCode())
                            && Objects.equals(binding.binding().getTaskCode(), arrange.getTaskCode())
                            && Objects.equals(binding.binding().getCanonicalIdentifier(), identifier)
                            && Integer.valueOf(1).equals(binding.binding().getIdentifierRouteVersion())
                            && Set.of("BIND", "USE", "RELEASE").contains(binding.action())
                            && binding.evidenceSnapshot() != null,
                    "时间规则缺少本次真实动态节点准备事实，不能以外码充当内部SN");
        }
        // 旧START/FINISH是MES本地时间字段；数据库与MES时区不一致时宁可阻断，不能错算8小时等偏移。
        if (rules.stream().anyMatch(rule -> "WORK_ORDER".equals(rule.calculation().scope()))) {
            require(Duration.between(accepted, LocalDateTime.now()).abs().compareTo(Duration.ofSeconds(30)) < 0,
                    "工序时间校验发现MES与数据库时区/时钟不一致，请统一运行时区并校时后再报工");
        }
        if (!visitRules && identifier != null && !identifier.isBlank()) {
            long returned = routeReturns.selectCount(new LambdaQueryWrapper<MesIdentifierRouteReturnEntity>()
                    .eq(MesIdentifierRouteReturnEntity::getTenantId, tenant)
                    .eq(MesIdentifierRouteReturnEntity::getFactoryCode, arrange.getFactoryCode())
                    .eq(MesIdentifierRouteReturnEntity::getTaskCode, arrange.getTaskCode())
                    .eq(MesIdentifierRouteReturnEntity::getIdentifier, identifier)
                    .in(MesIdentifierRouteReturnEntity::getStatus, "PENDING", "ACTIVE", "COMPLETED"));
            require(returned == 0, "该标识存在退回路线申请或历史，不能借用正常首轮时间窗口报工");
        }

        List<Object> checks = new ArrayList<>();
        List<Object> dependencyChecks = new ArrayList<>();
        boolean hasPauseRules = rules.stream().anyMatch(rule -> rule.calculation().deductTargetPause());
        List<String> lateRules = new ArrayList<>();
        LocalDateTime limitUntil = null;
        for (var resolved : rules) {
            var rule = resolved.calculation();
            MesProduceTaskEntity source = resolved.source();
            require(!Objects.equals(source.getId(), target.getId()), "时间规则来源不能是本工序");
            LocalDateTime sourceTime;
            String sourceReportId = null;
            String sourceBinding = null;
            String sourceVisitEvidence = null;
            if ("WORK_ORDER".equals(rule.scope())) {
                sourceTime = "START".equals(rule.sourceEvent()) ? source.getStartTime() : source.getFinishTime();
                require(sourceTime != null, prefix(rule) + "来源工序尚无实际" + ("START".equals(rule.sourceEvent()) ? "开工" : "完工") + "时刻");
                require(source.getStartTime() != null && !sourceTime.isBefore(source.getStartTime()), prefix(rule) + "来源工序时间顺序异常");
                if ("FINISH".equals(rule.sourceEvent()))
                    require("FINISH".equals(source.getStatus()), prefix(rule) + "来源工序尚未有效完工");
            } else {
                require(Set.of(ReportModeEnum.ONE_CODE, ReportModeEnum.PACKAGE_ONE_CODE, ReportModeEnum.TRANSFER_CARD).contains(mode),
                        prefix(rule) + "同标识规则只支持单件码、包装逐件或流转卡；数量/包装数量须配置工单范围");
                require(identifier != null && !identifier.isBlank(), prefix(rule) + "缺少规范生产标识");
                List<String> sourceModes = mode == ReportModeEnum.TRANSFER_CARD
                        ? List.of("TRANSFER_CARD") : List.of("ONE_CODE", "PACKAGE_ONE_CODE");
                var query = new LambdaQueryWrapper<MesTaskReportEntity>()
                        .eq(MesTaskReportEntity::getTenantId, tenant)
                        .eq(MesTaskReportEntity::getFactoryCode, arrange.getFactoryCode())
                        .eq(MesTaskReportEntity::getTaskCode, arrange.getTaskCode())
                        .eq(MesTaskReportEntity::getProduceTaskCode, source.getCode())
                        .eq(MesTaskReportEntity::getTransferCode, identifier)
                        .eq(MesTaskReportEntity::getStatus, "EFFECTIVE")
                        .gt(MesTaskReportEntity::getEffectiveReportNum, 0)
                        .in(MesTaskReportEntity::getReportMode, sourceModes);
                if (visitRules) {
                    sourceVisitEvidence = visitSource(arrange, identifier, source, mode, rule, visitContext);
                    var proof = readEvidence(sourceVisitEvidence, prefix(rule) + "来源轮次证据缺失");
                    require(proof.path("reportId").isTextual(), prefix(rule) + "来源报告身份不是精确文本");
                    query.eq(MesTaskReportEntity::getId, Long.valueOf(proof.path("reportId").asText()));
                }
                var candidates = reports.selectList(query.last("ORDER BY id LIMIT 2 FOR SHARE"));
                require(candidates.size() == 1, prefix(rule) + "同标识来源有效合格报工必须唯一，当前匹配" + candidates.size() + "条");
                var report = candidates.get(0);
                require(visitRules || ("NORMAL".equals(report.getExecutionKind()) && Integer.valueOf(1).equals(report.getIdentifierRouteVersion())),
                        prefix(rule) + "来源不是可证明的正常首轮标识事实");
                if (report.getIdentifierBindingId() != null) {
                    sourceBinding = reports.timingBindingSource(tenant, arrange.getFactoryCode(), report.getId());
                    require(sourceBinding != null && !sourceBinding.isBlank(), prefix(rule) + "来源动态占用原证据不完整");
                }
                sourceTime = report.getTimingAcceptedAt();
                require(sourceTime != null, prefix(rule) + "来源是缺少服务器接受时刻的历史报工，不能补造时间");
                sourceReportId = report.getId().toString();
            }
            require(!sourceTime.isAfter(accepted), prefix(rule) + "来源时间晚于当前服务器时间");
            LocalDateTime earliest = rule.minimum() == null ? null : sourceTime.plusSeconds(rule.minimum());
            LocalDateTime deadline = rule.maximum() == null ? null : sourceTime.plusSeconds(rule.maximum());
            // 例外绑定稳定来源及原基础窗口；当次扣除另行冻结，不能因时钟前进改变原申请身份。
            var check = new LinkedHashMap<String, Object>();
            check.put("rule", resolved.evidence());
            check.put("sourceTaskId", source.getId().toString());
            check.put("sourceReportId", sourceReportId);
            check.put("sourceBinding", sourceBinding);
            if (visitRules) check.put("sourceVisitEvidence", sourceVisitEvidence);
            check.put("sourceTime", sourceTime.toString());
            check.put("earliest", earliest == null ? null : earliest.toString());
            check.put("deadline", deadline == null ? null : deadline.toString());
            dependencyChecks.add(new LinkedHashMap<>(check));
            if (hasPauseRules || visitRules) {
                check.put("baseEarliest", check.get("earliest"));
                check.put("baseDeadline", check.get("deadline"));
            }
            if (rule.deductTargetPause()) {
                String pauseEvidence = timingEvents.pauseContext(arrange, sourceTime, accepted);
                long pauseMicros;
                try {
                    var parsed = new ObjectMapper().readTree(pauseEvidence);
                    require(parsed.path("pausedSeconds").isTextual(), "暂停扣除必须是精确文本秒数");
                    pauseMicros = new java.math.BigDecimal(parsed.path("pausedSeconds").asText())
                            .movePointRight(6).longValueExact();
                    require(pauseMicros >= 0, "暂停扣除不能为负数");
                } catch (CheckedException rejected) {
                    throw rejected;
                } catch (Exception rejected) {
                    throw new CheckedException("原派工暂停计时正文无效，未执行报工");
                }
                if (earliest != null) earliest = earliest.plusNanos(Math.multiplyExact(pauseMicros, 1000L));
                if (deadline != null) deadline = deadline.plusNanos(Math.multiplyExact(pauseMicros, 1000L));
                check.put("pauseEvidence", pauseEvidence);
            }
            require(earliest == null || !accepted.isBefore(earliest), prefix(rule) + "未到允许报工时间；最早 " + earliest
                    + "，还需等待 " + (earliest == null ? 0 : ceilingSeconds(accepted, earliest)) + " 秒");
            if (deadline != null) {
                LocalDateTime limit = rule.lateExceptionSeconds() == null ? deadline
                        : deadline.plusSeconds(Long.parseLong(rule.lateExceptionSeconds()));
                if (limitUntil == null || limit.isBefore(limitUntil)) limitUntil = limit;
                if (accepted.isAfter(deadline)) lateRules.add(rule.name());
            }
            check.put("earliest", earliest == null ? null : earliest.toString());
            check.put("deadline", deadline == null ? null : deadline.toString());
            checks.add(check);
        }
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("version", nodeRules ? NODE_EVIDENCE : visitRules ? "MES_PROCESS_TIMING_EVIDENCE_V5"
                : hasPauseRules ? "MES_PROCESS_TIMING_EVIDENCE_V4" : "MES_PROCESS_TIMING_EVIDENCE_V2");
        evidence.put("targetBindingSnapshot", binding == null ? null : binding.evidenceSnapshot());
        evidence.put("clock", hasPauseRules || visitRules ? "RULE_CLOCKS" : "SERVER_ACCEPTED_ELAPSED");
        if (hasPauseRules || visitRules) evidence.put("targetArrangeId", arrange.getId().toString());
        if (visitRules) evidence.put("targetVisitContext", visitContext);
        evidence.put("targetTaskId", target.getId().toString());
        evidence.put("acceptedAt", accepted.toString());
        evidence.put("identifier", identifier);
        evidence.put("checks", checks);
        if (nodeRules) addNodeContract(evidence, target);
        var dependency = new LinkedHashMap<String, Object>();
        dependency.put("version", nodeRules ? NODE_DEPENDENCY : visitRules ? "MES_TIMING_DEPENDENCY_V3"
                : hasPauseRules ? "MES_TIMING_DEPENDENCY_V2" : "MES_TIMING_DEPENDENCY_V1");
        if (hasPauseRules || visitRules) dependency.put("targetArrangeId", arrange.getId().toString());
        if (visitRules) dependency.put("targetVisitContext", visitContext);
        dependency.put("targetTaskId", target.getId().toString());
        dependency.put("identifier", identifier);
        dependency.put("checks", dependencyChecks);
        if (nodeRules) addNodeContract(dependency, target);
        return new Decision(accepted, MdmProcessTimingPolicy.encodeEvidence(evidence),
                MdmProcessTimingPolicy.encodeEvidence(dependency), limitUntil, List.copyOf(lateRules));
    }

    /** 数据库按实际批准轮次唯一取来源。零笔和多笔均是业务阻断，其他数据库错误交回上层处理。 */
    private String visitSource(MesTaskArrangeEntity arrange, String identifier, MesProduceTaskEntity source,
                               ReportModeEnum mode, MdmProcessTimingPolicy.Rule rule, String context) {
        try {
            return reports.timingVisitSource(arrange.getTenantId(), arrange.getFactoryCode(), arrange.getTaskCode(),
                    identifier, source.getCode(), mode.name(),
                    rule.sourceVisit() == null ? "ORIGINAL" : rule.sourceVisit(), context);
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql) {
                    String location = prefix(rule) + "来源工序任务 " + source.getCode() + " 中，标识 "
                            + identifier + " 在选定轮次";
                    if ("P0002".equals(sql.getSQLState())) {
                        throw new CheckedException(location + "没有有效合格报工，请核对原工序和标识");
                    }
                    if ("P0003".equals(sql.getSQLState())) {
                        throw new CheckedException(location + "有多笔有效合格报工，请核对来源记录");
                    }
                }
            }
            throw failure;
        }
    }

    /**
     * 正式返工是独立工单，来源只能取返工单已冻结的原实际工序，不能在单节点图中伪造前序。
     * 使用自然时间窗口；如工程方案明确配置例外硬上限，超时后仍须原报工人申请、异人批准并一次消费。
     */
    private Decision reworkTiming(MesTaskEntity order, MesProduceTaskEntity target,
                                  MesTaskArrangeEntity arrange, ReportModeEnum mode,
                                  String identifier, LocalDateTime accepted) {
        if (order == null || !"REWORK".equals(order.getResourceType())
                || order.getReworkTimingPolicySnapshot() == null
                || order.getReworkTimingPolicySnapshot().isBlank()) return null;
        MesReworkTimingPolicy policy = MesReworkTimingPolicy.parse(order.getReworkTimingPolicySnapshot());
        require(Objects.equals(order.getCode(), target.getTaskCode())
                        && Objects.equals(order.getReworkRouteNodeCode(), target.getSourceRouteNodeCode())
                        && Objects.equals(order.getReworkProcessCode(), target.getRouteCode()),
                "返工时间策略目标实际工序与批准节点不一致");
        MesProduceTaskEntity source = sourceTask(order, arrange);
        require(Objects.equals(source.getSourceRouteNodeCode(), order.getReworkRouteNodeCode())
                        && Objects.equals(source.getRouteCode(), order.getReworkProcessCode()),
                "返工时间来源实际工序与原批准节点/主工序不一致");

        var check = new LinkedHashMap<String, Object>();
        check.put("policy", policy);
        check.put("sourceOrderCode", order.getSourceTaskCode());
        check.put("sourceProduceTaskCode", source.getCode());
        check.put("sourceRouteNodeCode", source.getSourceRouteNodeCode());
        check.put("targetOrderCode", order.getCode());
        check.put("targetProduceTaskCode", target.getCode());
        check.put("acceptedAt", accepted.toString());
        check.put("identifier", identifier);
        if (!policy.enabled()) {
            return reworkDecision(check, accepted, null, List.of());
        }

        LocalDateTime sourceTime;
        if ("WORK_ORDER".equals(policy.scope())) {
            require(Duration.between(accepted, LocalDateTime.now()).abs().compareTo(Duration.ofSeconds(30)) < 0,
                    "返工时间校验发现MES与数据库时区/时钟不一致，请统一运行时区并校时后再报工");
            sourceTime = "START".equals(policy.sourceEvent()) ? source.getStartTime() : source.getFinishTime();
            require(sourceTime != null, "返工时间来源工序尚无实际" + ("START".equals(policy.sourceEvent()) ? "开工" : "完工") + "时刻");
            require(source.getStartTime() != null && !sourceTime.isBefore(source.getStartTime()),
                    "返工时间来源工序开完工时序异常");
            if ("FINISH".equals(policy.sourceEvent())) {
                require("FINISH".equals(source.getStatus()), "返工时间来源工序尚未有效完工");
            }
        } else {
            require(Set.of(ReportModeEnum.ONE_CODE, ReportModeEnum.PACKAGE_ONE_CODE,
                            ReportModeEnum.TRANSFER_CARD).contains(mode),
                    "返工同标识时间窗口只支持单件码、包装逐件或流转卡；数量类请选择整单口径");
            require(identifier != null && !identifier.isBlank(), "返工同标识时间窗口缺少规范生产标识");
            List<String> sourceModes = mode == ReportModeEnum.TRANSFER_CARD
                    ? List.of("TRANSFER_CARD") : List.of("ONE_CODE", "PACKAGE_ONE_CODE");
            var candidates = reports.selectList(new LambdaQueryWrapper<MesTaskReportEntity>()
                    .eq(MesTaskReportEntity::getTenantId, order.getTenantId())
                    .eq(MesTaskReportEntity::getFactoryCode, order.getFactoryCode())
                    .eq(MesTaskReportEntity::getTaskCode, order.getSourceTaskCode())
                    .eq(MesTaskReportEntity::getProduceTaskCode, source.getCode())
                    .eq(MesTaskReportEntity::getTransferCode, identifier)
                    .eq(MesTaskReportEntity::getStatus, "EFFECTIVE")
                    .gt(MesTaskReportEntity::getEffectiveReportNum, 0)
                    .in(MesTaskReportEntity::getReportMode, sourceModes)
                    .last("ORDER BY id LIMIT 2 FOR SHARE"));
            require(candidates.size() == 1,
                    "返工同标识时间来源有效合格报工必须唯一，当前匹配" + candidates.size() + "条");
            MesTaskReportEntity sourceReport = candidates.get(0);
            sourceTime = sourceReport.getTimingAcceptedAt();
            require(sourceTime != null, "返工来源是缺少服务器接受时刻的历史报工，不能补造跨工单时间");
            check.put("sourceReportId", sourceReport.getId().toString());
        }
        require(!sourceTime.isAfter(accepted), "返工时间来源晚于当前服务器接受时刻");
        LocalDateTime earliest = policy.minimum() == null ? null : sourceTime.plusSeconds(policy.minimum());
        LocalDateTime deadline = policy.maximum() == null ? null : sourceTime.plusSeconds(policy.maximum());
        require(earliest == null || !accepted.isBefore(earliest), "返工尚未到允许报工时间；最早 " + earliest
                + "，还需等待 " + (earliest == null ? 0 : ceilingSeconds(accepted, earliest)) + " 秒");
        LocalDateTime limitUntil = deadline;
        if (deadline != null && policy.lateExceptionSeconds() != null) {
            limitUntil = deadline.plusSeconds(Long.parseLong(policy.lateExceptionSeconds()));
        }
        require(deadline == null || !accepted.isAfter(deadline) || policy.lateExceptionSeconds() != null,
                "返工已超过批准时间窗口；最晚 " + deadline
                        + "，当前方案未允许发起超时例外");
        require(limitUntil == null || !accepted.isAfter(limitUntil),
                "返工已超过工程批准的例外硬上限 " + limitUntil
                        + "；须重新评估并批准新的返工执行方案");
        check.put("sourceTime", sourceTime.toString());
        check.put("earliest", earliest == null ? null : earliest.toString());
        check.put("deadline", deadline == null ? null : deadline.toString());
        List<String> late = deadline != null && accepted.isAfter(deadline)
                ? List.of("返工跨工单时间窗口") : List.of();
        return reworkDecision(check, accepted, limitUntil, late);
    }

    private Decision reworkDecision(LinkedHashMap<String, Object> check, LocalDateTime accepted,
                                    LocalDateTime limitUntil, List<String> lateRules) {
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("version", "MES_REWORK_TIMING_EVIDENCE_V1");
        evidence.put("clock", "SERVER_ACCEPTED_ELAPSED");
        evidence.put("check", check);
        var stableCheck = new LinkedHashMap<>(check);
        stableCheck.remove("acceptedAt");
        var dependency = new LinkedHashMap<String, Object>();
        dependency.put("version", "MES_REWORK_TIMING_DEPENDENCY_V1");
        dependency.put("check", stableCheck);
        return new Decision(accepted, MdmProcessTimingPolicy.encodeEvidence(evidence),
                MdmProcessTimingPolicy.encodeEvidence(dependency), limitUntil, List.copyOf(lateRules));
    }

    private MesTaskEntity currentOrder(MesTaskArrangeEntity arrange) {
        var matches = orders.selectList(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, arrange.getTenantId())
                .eq(MesTaskEntity::getFactoryCode, arrange.getFactoryCode())
                .eq(MesTaskEntity::getCode, arrange.getTaskCode()).last("ORDER BY id LIMIT 2 FOR SHARE"));
        require(matches.size() == 1, "时间规则生产工单未唯一归属当前租户工厂");
        return matches.get(0);
    }

    private MesProduceTaskEntity sourceTask(MesTaskEntity order, MesTaskArrangeEntity arrange) {
        require(order.getSourceTaskCode() != null && !order.getSourceTaskCode().isBlank()
                        && order.getSourceProduceTaskCode() != null && !order.getSourceProduceTaskCode().isBlank(),
                "返工时间策略缺少原生产工单或原实际工序身份");
        require(!Objects.equals(order.getCode(), order.getSourceTaskCode()),
                "返工跨工单时间策略不能将本返工工单伪装为原来源工单");
        var matches = tasks.selectList(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getTenantId, order.getTenantId())
                .eq(MesProduceTaskEntity::getFactoryCode, arrange.getFactoryCode())
                .eq(MesProduceTaskEntity::getTaskCode, order.getSourceTaskCode())
                .eq(MesProduceTaskEntity::getCode, order.getSourceProduceTaskCode())
                .last("ORDER BY id LIMIT 2 FOR SHARE"));
        require(matches.size() == 1, "返工时间来源实际工序未唯一归属原生产工单");
        return matches.get(0);
    }

    /**
     * 实际任务编码在组织/工单内必须唯一；锁住真实行避免计时读取期间改变来源时刻。
     */
    private MesProduceTaskEntity actualTask(MesTaskArrangeEntity arrange, String taskCode) {
        require(taskCode != null && !taskCode.isBlank(), "时间规则缺少实际工序任务编码");
        var matches = tasks.selectList(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesProduceTaskEntity::getFactoryCode, arrange.getFactoryCode())
                .eq(MesProduceTaskEntity::getTaskCode, arrange.getTaskCode())
                .eq(MesProduceTaskEntity::getCode, taskCode).last("ORDER BY id LIMIT 2 FOR SHARE"));
        require(matches.size() == 1, "时间规则实际任务【" + taskCode + "】未唯一归属当前工单");
        return matches.get(0);
    }

    private MesProduceTaskEntity uniqueTask(MesTaskArrangeEntity arrange, String processCode) {
        var matches = tasks.selectList(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesProduceTaskEntity::getFactoryCode, arrange.getFactoryCode())
                .eq(MesProduceTaskEntity::getTaskCode, arrange.getTaskCode())
                .eq(MesProduceTaskEntity::getRouteCode, processCode).last("ORDER BY id LIMIT 2 FOR SHARE"));
        require(matches.size() == 1, "时间规则工序【" + processCode + "】未唯一归属当前工单，禁止猜测加工轮次");
        return matches.get(0);
    }

    /**
     * 接受时刻及原始JSON证据由服务器生成，不能取自报工DTO。
     */
    public record Decision(LocalDateTime acceptedAt, String evidenceJson, String dependencyJson,
                           LocalDateTime limitUntil, List<String> lateRules) {
    }

    /**
     * 计算参数可复用主工序规则；实际来源和输出证据始终保留各自原生协议。
     */
    private record ResolvedRule(MdmProcessTimingPolicy.Rule calculation, Object evidence, MesProduceTaskEntity source) {
    }
}
