package com.kitex.mom.plan.service.solver;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 纯值输入，不携带实体、数据库连接、远端客户端或写入回调。
 */
public record SequenceProblem(String contractVersion, String strategy, int windowDays, boolean preserveRuleOrder,
                              String observation, String catalogSnapshot, List<Job> jobs,
                              Map<String, Map<String, Long>> transitionMinutes) {
    public SequenceProblem {
        jobs = List.copyOf(jobs);
        var copied = new TreeMap<String, Map<String, Long>>();
        transitionMinutes.forEach((key, value) -> copied.put(key, Collections.unmodifiableMap(new TreeMap<>(value))));
        transitionMinutes = Collections.unmodifiableMap(copied);
    }

    public record Job(String id, String productCode, String family, LocalDate dueDate) {
    }
}
