package com.kitex.mom.plan.service.solver;

import com.kitex.mom.plan.dto.PlnSequenceSolverSpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

@Component
public class GreedyWindowSequenceSolver implements SequenceSolver {
    public PlnSequenceSolverSpec specification() {
        return new PlnSequenceSolverSpec("GREEDY_WINDOW", "规则交期窗口贪心", "1.0.0", "APS_SEQUENCE_V1", 1000, 1, 1_000_000, 0);
    }

    public SequenceSolution solve(SequenceProblem p, Runnable checkpoint) {
        BitSet used = new BitSet();
        List<String> result = new ArrayList<>();
        long objective = 0, expansions = 0;
        int previous = -1;
        while (result.size() < p.jobs().size()) {
            checkpoint.run();
            var earliest = SequenceRules.earliest(p, used);
            int best = -1;
            long bestScore = Long.MAX_VALUE;
            for (int i = 0; i < p.jobs().size(); i++) {
                if ((i & 63) == 0) checkpoint.run();
                if (p.preserveRuleOrder() ? i != result.size() : !SequenceRules.eligible(p, i, used, earliest))
                    continue;
                long score = SequenceRules.step(p, previous, i, earliest);
                expansions++;
                if (best < 0 || score < bestScore || score == bestScore && SequenceRules.tie(p, i, best) < 0) {
                    best = i;
                    bestScore = score;
                }
            }
            if (best < 0) throw new IllegalStateException("交期窗口未形成可选计划");
            used.set(best);
            result.add(p.jobs().get(best).id());
            objective = Math.addExact(objective, bestScore);
            previous = best;
        }
        return new SequenceSolution(result, objective, objective, expansions, p.preserveRuleOrder() ? "RULE_ORDER_PRESERVED" : "COMPLETED", "GREEDY");
    }
}
