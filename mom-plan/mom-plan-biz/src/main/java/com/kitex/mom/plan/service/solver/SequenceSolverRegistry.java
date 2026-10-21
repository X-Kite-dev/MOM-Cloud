package com.kitex.mom.plan.service.solver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.plan.dto.PlnSequenceSolverSpec;
import com.kitex.mom.plan.service.SchedulingExecutionContext;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 只准返回来源排列；独立重算目标和交期窗口，不信任provider自报成功。
 */
@Component
public class SequenceSolverRegistry {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final Map<String, SequenceSolver> providers;

    public SequenceSolverRegistry(List<SequenceSolver> solvers) {
        Map<String, SequenceSolver> map = new TreeMap<>();
        for (var solver : solvers) {
            var spec = solver.specification();
            if (spec == null || map.putIfAbsent(spec.code(), solver) != null)
                throw new IllegalStateException("排序求解器描述缺失或编码重复");
        }
        providers = Collections.unmodifiableMap(map);
    }

    public static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception ex) {
            throw new CheckedException("排序证据序列化失败，不能丢弃证据后继续");
        }
    }

    public static String hash(String s) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public String normalize(String code) {
        return code == null || code.isBlank() ? "GREEDY_WINDOW" : code.trim().toUpperCase(Locale.ROOT);
    }

    public List<PlnSequenceSolverSpec> catalog() {
        return providers.values().stream().map(SequenceSolver::specification).toList();
    }

    public PlnSequenceSolverSpec specification(String code) {
        var provider = providers.get(normalize(code));
        require(provider != null, "排序求解器不存在，不允许静默回退");
        return provider.specification();
    }

    public String specificationJson(String code) {
        return json(specification(code));
    }

    public Outcome solve(String code, SequenceProblem problem) {
        var spec = specification(code);
        require("APS_SEQUENCE_V1".equals(spec.contractVersion()) && spec.contractVersion().equals(problem.contractVersion()), "排序契约版本不兼容");
        require(problem.jobs() != null && !problem.jobs().isEmpty() && problem.jobs().size() <= Math.min(1000, spec.maxPlans()), "排序范围超出所选求解器上限，须显式缩小范围或选择其他求解器");
        require(Set.of("DELIVERY_FIRST", "BALANCED_GLOBAL", "CHANGEOVER_FIRST").contains(problem.strategy()) && problem.windowDays() >= 0 && problem.windowDays() <= 30, "排序策略或交期窗口无效");
        require(!"DELIVERY_FIRST".equals(problem.strategy()) || problem.preserveRuleOrder(), "交期优先策略必须保留原规则顺序");
        Set<String> ids = new HashSet<>();
        for (var job : problem.jobs())
            require(job != null && job.id() != null && job.id().matches("[1-9][0-9]{0,18}") && ids.add(job.id()), "排序来源ID缺失或重复");
        problem.transitionMinutes().forEach((from, targets) -> targets.forEach((to, cost) -> require(cost != null && cost >= 0 && cost <= 1_000_000_000L, "换型成本超出契约范围，不能溢出后选优")));
        String input = json(problem);
        require(input.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 4 * 1024 * 1024, "排序输入超过4MiB，不能截断证据后求解");
        SchedulingExecutionContext.check();
        long start = System.nanoTime();
        var solution = providers.get(spec.code()).solve(problem, SchedulingExecutionContext::check);
        SchedulingExecutionContext.check();
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        require(solution != null, "排序求解器未返回完整结果");
        long score = SequenceRules.validate(problem, solution.orderedIds());
        var baseline = new GreedyWindowSequenceSolver().solve(problem, SchedulingExecutionContext::check);
        require(solution.objective() == score && solution.baselineObjective() == baseline.objective() && score <= baseline.objective(), "排序结果目标不一致或劣于原贪心基线，拒绝发布");
        require(solution.expansions() >= 0 && Set.of("COMPLETED", "RULE_ORDER_PRESERVED", "SEARCH_LIMIT_INCUMBENT").contains(solution.termination()), "排序工作量或终止状态不可核验");
        return new Outcome(solution, json(Map.of("version", "APS_SEQUENCE_PROOF_V1", "solver", spec, "input", problem, "inputFingerprint", hash(input),
                "output", solution, "solverElapsedMillis", elapsed, "validation", "PERMUTATION_WINDOW_OBJECTIVE_VERIFIED",
                "boundary", "排序目标非实际有限产能工期，资源和发布约束须继续独立校验")));
    }

    private void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }

    public record Outcome(SequenceSolution solution, String evidenceJson) {
    }
}
