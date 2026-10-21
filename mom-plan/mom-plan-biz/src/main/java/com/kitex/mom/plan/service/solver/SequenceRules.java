package com.kitex.mom.plan.service.solver;

import com.kitex.common.core.exception.CheckedException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * V1目标是换型估计分钟+动态交期偏移+原规则次序惩罚，不是实际工期或财务收益。
 */
final class SequenceRules {
    private SequenceRules() {
    }

    static LocalDate earliest(SequenceProblem p, BitSet used) {
        LocalDate first = LocalDate.MAX;
        for (int i = 0; i < p.jobs().size(); i++)
            if (!used.get(i) && p.jobs().get(i).dueDate() != null && p.jobs().get(i).dueDate().isBefore(first))
                first = p.jobs().get(i).dueDate();
        return first;
    }

    static boolean eligible(SequenceProblem p, int index, BitSet used, LocalDate earliest) {
        if (used.get(index)) return false;
        var date = p.jobs().get(index).dueDate();
        return earliest.equals(LocalDate.MAX) || date != null && ChronoUnit.DAYS.between(earliest, date) <= p.windowDays();
    }

    static long transition(SequenceProblem p, String from, String to) {
        if (from == null || from.isBlank() || to == null || to.isBlank() || from.equals(to)) return 0;
        return p.transitionMinutes().getOrDefault(from, Map.of()).getOrDefault(to, 240L);
    }

    static long step(SequenceProblem p, int previous, int current, LocalDate earliest) {
        var job = p.jobs().get(current);
        long delta = job.dueDate() == null || earliest.equals(LocalDate.MAX) ? 0 : Math.max(0, ChronoUnit.DAYS.between(earliest, job.dueDate()));
        boolean changeover = "CHANGEOVER_FIRST".equals(p.strategy());
        return Math.addExact(transition(p, previous < 0 ? null : p.jobs().get(previous).family(), job.family()),
                Math.addExact(Math.multiplyExact(delta, changeover ? 15L : 120L), Math.multiplyExact(current, changeover ? 1L : 5L)));
    }

    static int tie(SequenceProblem p, int left, int right) {
        int due = Objects.requireNonNullElse(p.jobs().get(left).dueDate(), LocalDate.MAX).compareTo(Objects.requireNonNullElse(p.jobs().get(right).dueDate(), LocalDate.MAX));
        return due == 0 ? Integer.compare(left, right) : due;
    }

    static long validate(SequenceProblem p, List<String> ids) {
        if (ids.size() != p.jobs().size()) throw new CheckedException("排序求解器漏掉或增添来源计划");
        Map<String, Integer> indices = new HashMap<>();
        for (int i = 0; i < p.jobs().size(); i++) indices.put(p.jobs().get(i).id(), i);
        BitSet used = new BitSet();
        int previous = -1;
        long score = 0;
        for (int rank = 0; rank < ids.size(); rank++) {
            Integer i = indices.get(ids.get(rank));
            if (i == null || used.get(i)) throw new CheckedException("排序求解器返回未知/重复计划");
            LocalDate earliest = earliest(p, used);
            if (p.preserveRuleOrder() ? i != rank : !eligible(p, i, used, earliest))
                throw new CheckedException("排序结果绕过规则次序或交期窗口约束");
            score = Math.addExact(score, step(p, previous, i, earliest));
            used.set(i);
            previous = i;
        }
        return score;
    }
}
