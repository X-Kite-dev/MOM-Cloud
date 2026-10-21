package com.kitex.mom.plan.service.solver;

import com.kitex.mom.plan.dto.PlnSequenceSolverSpec;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 确定性宽度32束搜索；始终保留完整贪心基线，未完成的路径绝不能输出为生产顺序。
 */
@Component
public class BeamWindowSequenceSolver implements SequenceSolver {
    public PlnSequenceSolverSpec specification() {
        return new PlnSequenceSolverSpec("BEAM_WINDOW", "交期窗口多路径搜索", "1.0.0", "APS_SEQUENCE_V1", 200, 32, 1_000_000, 15000);
    }

    public SequenceSolution solve(SequenceProblem p, Runnable checkpoint) {
        var baseline = new GreedyWindowSequenceSolver().solve(p, checkpoint);
        if (p.preserveRuleOrder()) return baseline;
        var spec = specification();
        long start = System.nanoTime(), expansions = 0;
        List<State> beam = List.of(new State(new int[0], new BitSet(), 0));
        Comparator<State> ordering = (a, b) -> {
            int value = Long.compare(a.cost, b.cost);
            if (value != 0) return value;
            for (int i = 0; i < Math.min(a.path.length, b.path.length); i++) {
                value = Integer.compare(a.path[i], b.path[i]);
                if (value != 0) return value;
            }
            return Integer.compare(a.path.length, b.path.length);
        };
        for (int depth = 0; depth < p.jobs().size(); depth++) {
            List<State> next = new ArrayList<>();
            for (State state : beam) {
                checkpoint.run();
                var earliest = SequenceRules.earliest(p, state.used);
                int previous = state.path.length == 0 ? -1 : state.path[state.path.length - 1];
                for (int candidate = 0; candidate < p.jobs().size(); candidate++) {
                    if ((candidate & 63) == 0) checkpoint.run();
                    if (expansions >= spec.maxExpansions() || (System.nanoTime() - start) / 1_000_000 >= spec.searchBudgetMillis())
                        return new SequenceSolution(baseline.orderedIds(), baseline.objective(), baseline.objective(), expansions, "SEARCH_LIMIT_INCUMBENT", "GREEDY_INCUMBENT");
                    if (!SequenceRules.eligible(p, candidate, state.used, earliest)) continue;
                    expansions++;
                    var path = Arrays.copyOf(state.path, state.path.length + 1);
                    path[path.length - 1] = candidate;
                    BitSet used = (BitSet) state.used.clone();
                    used.set(candidate);
                    next.add(new State(path, used, Math.addExact(state.cost, SequenceRules.step(p, previous, candidate, earliest))));
                }
            }
            next.sort(ordering);
            beam = new ArrayList<>(next.subList(0, Math.min(spec.beamWidth(), next.size())));
            if (beam.isEmpty()) throw new IllegalStateException("束搜索未形成完整候选");
        }
        checkpoint.run();
        State best = beam.get(0);
        if (best.cost >= baseline.objective())
            return new SequenceSolution(baseline.orderedIds(), baseline.objective(), baseline.objective(), expansions, "COMPLETED", "GREEDY_INCUMBENT");
        return new SequenceSolution(Arrays.stream(best.path).mapToObj(i -> p.jobs().get(i).id()).toList(), best.cost, baseline.objective(), expansions, "COMPLETED", "BEAM");
    }

    private record State(int[] path, BitSet used, long cost) {
    }
}
