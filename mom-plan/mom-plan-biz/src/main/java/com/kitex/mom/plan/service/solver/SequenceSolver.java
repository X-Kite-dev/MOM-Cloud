package com.kitex.mom.plan.service.solver;

import com.kitex.mom.plan.dto.PlnSequenceSolverSpec;

public interface SequenceSolver {
    PlnSequenceSolverSpec specification();

    SequenceSolution solve(SequenceProblem problem, Runnable checkpoint);
}
