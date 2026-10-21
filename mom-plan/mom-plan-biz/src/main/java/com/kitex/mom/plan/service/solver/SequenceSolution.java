package com.kitex.mom.plan.service.solver;

import java.util.List;

public record SequenceSolution(List<String> orderedIds, long objective, long baselineObjective, long expansions,
                               String termination, String chosenMethod) {
    public SequenceSolution {
        orderedIds = List.copyOf(orderedIds);
    }
}
