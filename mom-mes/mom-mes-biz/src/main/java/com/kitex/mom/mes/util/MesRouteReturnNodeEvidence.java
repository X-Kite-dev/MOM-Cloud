package com.kitex.mom.mes.util;

import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONConfig;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.entity.MesIdentifierRouteReturnEntity;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 节点退回的纯证据解释器：原正常履历、批准片段及本轮可执行节点。
 * 不读取当前工程主数据，不按主工序或显示序号猜测实际执行，也不办理数量/库存写账。
 */
public final class MesRouteReturnNodeEvidence {
    public static final String VERSION = "NODE_ROUTE_RETURN_V2";

    private MesRouteReturnNodeEvidence() {
    }

    /** 同一次数据库读取所得的完整原事实；只用于服务端，不接受客户端传入。 */
    public record Evidence(MesTaskEntity task, String identifier, List<MesProduceTaskEntity> routes,
                           List<MesProduceTaskEntity> passedRoutes, MesProduceTaskEntity source,
                           String fingerprint, String sourceSnapshot, MesFrozenExecutionGraph graph) {
    }

    public static Evidence capture(MesTaskEntity task, String identifier, List<MesProduceTaskEntity> routes,
                                   List<MesTaskReportEntity> reports, List<MesTaskArrangeEntity> arrangements) {
        require(task != null && task.getId() != null && task.getTenantId() != null
                && identifier != null && !identifier.isBlank(), "节点退回缺少真实工单或规范标识");
        require(routes != null && routes.size() >= 2 && routes.size() <= 200,
                "节点退回须有2至200道完整实际工序");
        MesFrozenExecutionGraph graph = MesFrozenExecutionGraph.of(routes);
        require(routes.stream().allMatch(route -> MesFrozenExecutionGraph.NODE_EXECUTION_V1
                        .equals(route.getRouteExecutionSchemaVersion())
                        && Objects.equals(task.getTenantId(), route.getTenantId())
                        && Objects.equals(task.getFactoryCode(), route.getFactoryCode())
                        && Objects.equals(task.getCode(), route.getTaskCode())
                        && Objects.equals(task.getProcessRouteCode(), route.getProcessRouteCode())),
                "节点退回必须归属原完整节点工单");
        Map<String, MesProduceTaskEntity> byTask = routes.stream().collect(Collectors.toMap(
                MesProduceTaskEntity::getCode, Function.identity()));
        Map<String, MesTaskArrangeEntity> byArrange = arrangements(arrangements);
        List<MesTaskReportEntity> facts = reports.stream().filter(MesPredecessorPassEvidence::effective)
                .filter(report -> report.getRouteReturnId() == null
                        && (report.getExecutionKind() == null || "NORMAL".equals(report.getExecutionKind()))
                        && (report.getIdentifierRouteVersion() == null || report.getIdentifierRouteVersion() == 1))
                .sorted(Comparator.comparing(MesTaskReportEntity::getId)).toList();
        require(!facts.isEmpty() && facts.size() <= 10000, "原正常过站事实为空或超过核验上限");
        Set<Long> ids = new HashSet<>();
        Set<String> passedCodes = new HashSet<>();
        for (MesTaskReportEntity report : facts) {
            assertReport(task, identifier, report, byTask, byArrange);
            require(ids.add(report.getId()), "原正常过站事实ID重复");
            passedCodes.add(report.getProduceTaskCode());
        }
        List<MesProduceTaskEntity> passed = routes.stream().filter(row -> passedCodes.contains(row.getCode())).toList();
        require(passed.size() >= 2, "该标识至少通过两道正常工序后才能退回上序");
        List<MesProduceTaskEntity> frontiers = passed.stream().filter(row -> graph.descendantDepths(row)
                .keySet().stream().noneMatch(passedCodes::contains)).toList();
        require(frontiers.size() == 1, "已过工序存在多个未汇合分支，不能猜测恢复节点；请先完成汇合或使用正式返工路线");
        MesProduceTaskEntity source = frontiers.get(0);
        require(!Boolean.TRUE.equals(source.getLastRoute()), "末工序已形成产出事实，必须走报工冲销及正式返工工单");
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("version", VERSION);
        snapshot.put("tenantId", task.getTenantId().toString());
        snapshot.put("factoryCode", task.getFactoryCode());
        snapshot.put("taskId", task.getId().toString());
        snapshot.put("taskCode", task.getCode());
        snapshot.put("identifier", identifier);
        snapshot.put("processRouteSnapshot", task.getProcessRouteSnapshot());
        snapshot.put("executionNodes", MesReportReverseNodeEvidence.executionNodes(routes));
        snapshot.put("reports", facts.stream().map(MesRouteReturnNodeEvidence::reportMap).toList());
        String raw = json(snapshot);
        require(raw.getBytes(StandardCharsets.UTF_8).length <= 16 * 1024 * 1024, "节点退回完整证据超过16MiB，不能截断后执行");
        return new Evidence(task, identifier, List.copyOf(routes), passed, source, hash(raw), raw, graph);
    }

    /** 只列出真正已过祖先，不以显示顺序比较两个分支。 */
    public static List<MesProduceTaskEntity> targets(Evidence evidence) {
        return evidence.passedRoutes().stream().filter(row -> evidence.graph().descendantDepths(row)
                .containsKey(evidence.source().getCode())).toList();
    }

    public static MesProduceTaskEntity byActualTask(Evidence evidence, String taskCode) {
        return evidence.routes().stream().filter(row -> Objects.equals(taskCode, row.getCode())).findFirst()
                .orElseThrow(() -> new CheckedException("退回目标实际工序不属于原完整图"));
    }

    /**
     * 取目标到恢复节点的全部真实路径。允许内部并行，所有外部直接前序须有原正常事实。
     * 最长深度只决定确定性展示顺序；执行准入单独依据每条真实边。
     */
    public static List<MesProduceTaskEntity> segment(Evidence evidence, MesProduceTaskEntity target) {
        evidence.graph().key(target);
        Map<String, Integer> depths = new LinkedHashMap<>(evidence.graph().descendantDepths(target));
        require(depths.containsKey(evidence.source().getCode()), "目标不是恢复节点的真实已过上序");
        depths.put(target.getCode(), 0);
        List<MesProduceTaskEntity> segment = evidence.routes().stream()
                .filter(row -> depths.containsKey(row.getCode()) && (row.getCode().equals(evidence.source().getCode())
                        || evidence.graph().descendantDepths(row).containsKey(evidence.source().getCode())))
                .sorted(Comparator.comparingInt((MesProduceTaskEntity row) -> depths.get(row.getCode()))
                        .thenComparing(MesProduceTaskEntity::getCode)).toList();
        Set<String> passed = evidence.passedRoutes().stream().map(MesProduceTaskEntity::getCode).collect(Collectors.toSet());
        require(segment.size() >= 2 && segment.stream().allMatch(row -> passed.contains(row.getCode())),
                "批准片段存在未正常通过的节点，不能用退回代替首次生产");
        require(segment.stream().flatMap(row -> evidence.graph().predecessors(row).stream())
                .allMatch(row -> passed.contains(row.getCode())), "批准片段的前序事实不完整，不能借用其他分支");
        return segment;
    }

    /** 原申请全部文本和精确身份均需一致；不能借当前工程重新生成一个看似相同的批准。 */
    public static void assertFrozen(MesIdentifierRouteReturnEntity stored, Evidence current) {
        require(VERSION.equals(stored.getReturnSchemaVersion())
                        && Objects.equals(stored.getSourceFingerprint(), current.fingerprint())
                        && Objects.equals(stored.getSourceSnapshot(), current.sourceSnapshot())
                        && Objects.equals(stored.getTaskCode(), current.task().getCode())
                        && Objects.equals(stored.getTenantId(), current.task().getTenantId())
                        && Objects.equals(stored.getFactoryCode(), current.task().getFactoryCode())
                        && Objects.equals(stored.getIdentifier(), current.identifier()),
                "退回申请后的原正常事实或完整图已变化，请核对原申请，禁止重新解释批准");
        MesProduceTaskEntity target = byActualTask(current, stored.getTargetProduceTaskCode());
        MesProduceTaskEntity source = current.source();
        require(Objects.equals(stored.getSourceProduceTaskCode(), source.getCode())
                        && Objects.equals(stored.getSourceRouteNodeCode(), source.getSourceRouteNodeCode())
                        && Objects.equals(stored.getSourceRouteCode(), source.getRouteCode())
                        && Objects.equals(stored.getTargetRouteNodeCode(), target.getSourceRouteNodeCode())
                        && Objects.equals(stored.getTargetRouteCode(), target.getRouteCode())
                        && Objects.equals(stored.getResumeProduceTaskCode(), source.getCode())
                        && Objects.equals(stored.getResumeRouteNodeCode(), source.getSourceRouteNodeCode())
                        && Objects.equals(stored.getResumeRouteCode(), source.getRouteCode())
                        && Objects.equals(stored.getRouteSnapshot(), json(segment(current, target)
                                .stream().map(MesRouteReturnNodeEvidence::routeMap).toList())),
                "批准退回的来源、目标、恢复节点或片段与原图不一致");
    }

    /** 返回本轮未执行且片段内全部前序已执行的节点；空集仅表示整片段完成。 */
    public static List<MesProduceTaskEntity> ready(MesIdentifierRouteReturnEntity stored, Evidence current,
                                                  List<MesTaskReportEntity> reports,
                                                  List<MesTaskArrangeEntity> arrangements) {
        assertFrozen(stored, current);
        List<MesProduceTaskEntity> segment = segment(current, byActualTask(current, stored.getTargetProduceTaskCode()));
        Map<String, MesProduceTaskEntity> members = segment.stream().collect(Collectors.toMap(
                MesProduceTaskEntity::getCode, Function.identity()));
        Map<String, MesTaskArrangeEntity> byArrange = arrangements(arrangements);
        Set<String> passed = new HashSet<>();
        for (MesTaskReportEntity report : reports) {
            require(MesPredecessorPassEvidence.effective(report)
                            && Objects.equals(stored.getId(), report.getRouteReturnId())
                            && Objects.equals(stored.getVersionNo(), report.getIdentifierRouteVersion())
                            && "ROUTE_RETURN".equals(report.getExecutionKind()),
                    "本轮重走存在失效或不属于原批准的报告，不能忽略后继续执行");
            assertReport(current.task(), current.identifier(), report, members, byArrange);
            require(passed.add(report.getProduceTaskCode()), "本轮重走同一节点重复报工");
        }
        for (String code : passed) {
            require(current.graph().predecessors(members.get(code)).stream()
                    .filter(parent -> members.containsKey(parent.getCode())).allMatch(parent -> passed.contains(parent.getCode())),
                    "本轮重走履历跳过批准片段前序，禁止继续");
        }
        return segment.stream().filter(row -> !passed.contains(row.getCode()))
                .filter(row -> current.graph().predecessors(row).stream()
                        .filter(parent -> members.containsKey(parent.getCode())).allMatch(parent -> passed.contains(parent.getCode())))
                .toList();
    }

    public static Map<String, Object> routeMap(MesProduceTaskEntity route) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("produceTaskCode", route.getCode());
        value.put("routeCode", route.getRouteCode());
        value.put("routeName", route.getRouteName());
        value.put("routeOrder", route.getRouteOrder());
        value.put("predecessorCodes", csv(route.getPredecessorCodes()));
        value.put("lastRoute", Boolean.TRUE.equals(route.getLastRoute()));
        value.put("produceTaskId", route.getId().toString());
        value.put("sourceRouteNodeCode", route.getSourceRouteNodeCode());
        value.put("predecessorNodeCodes", csv(route.getPredecessorNodeCodes()));
        value.put("successorNodeCodes", csv(route.getSuccessorNodeCodes()));
        return value;
    }

    /** 新协议固定字段即使为NULL也保留，与数据库JSON结构核验一致；不应用到历史协议。 */
    public static String json(Object value) {
        return JSONUtil.toJsonStr(value, JSONConfig.create().setIgnoreNullValue(false));
    }

    private static Map<String, String> reportMap(MesTaskReportEntity report) {
        Map<String, String> value = new LinkedHashMap<>();
        value.put("reportId", report.getId().toString());
        value.put("produceTaskCode", report.getProduceTaskCode());
        value.put("arrangeCode", report.getArrangeCode());
        value.put("effectiveQuantity", (report.getEffectiveReportNum() == null ? report.getReportNum()
                : report.getEffectiveReportNum()).stripTrailingZeros().toPlainString());
        value.put("status", Objects.toString(report.getStatus(), ""));
        return value;
    }

    private static Map<String, MesTaskArrangeEntity> arrangements(List<MesTaskArrangeEntity> rows) {
        Map<String, MesTaskArrangeEntity> result = new LinkedHashMap<>();
        for (MesTaskArrangeEntity row : rows) {
            require(row != null && row.getCode() != null && result.putIfAbsent(row.getCode(), row) == null,
                    "真实派工缺失或编码不唯一，不能认领过站事实");
        }
        return result;
    }

    private static void assertReport(MesTaskEntity owner, String identifier, MesTaskReportEntity report,
                                     Map<String, MesProduceTaskEntity> tasks, Map<String, MesTaskArrangeEntity> arrangements) {
        require(Objects.equals(owner.getTenantId(), report.getTenantId())
                        && Objects.equals(owner.getFactoryCode(), report.getFactoryCode())
                        && Objects.equals(owner.getCode(), report.getTaskCode())
                        && Objects.equals(identifier, report.getTransferCode()), "过站报告不属于原组织、工单或标识");
        require(report.getIdentifierBindingId() == null, "动态换码报告尚未接入退回实例生命周期，不能继承原占用");
        MesTaskArrangeEntity arrange = arrangements.get(report.getArrangeCode());
        MesFrozenExecutionGraph.assertArrangement(tasks.get(report.getProduceTaskCode()), arrange);
        MesReportReverseNodeEvidence.assertReport(report, arrange);
    }

    private static List<String> csv(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isEmpty()).distinct().toList();
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK缺少SHA-256算法", exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
