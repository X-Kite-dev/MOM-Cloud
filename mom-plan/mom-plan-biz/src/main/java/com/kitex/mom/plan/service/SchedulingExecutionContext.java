package com.kitex.mom.plan.service;

import com.kitex.mom.plan.entity.PlnSchedulingRuleSetEntity;

/**
 * 仅当前受控运行线程可安装；远端调用不可取消，返回后和本地提交前必须重新检查。
 */
public final class SchedulingExecutionContext {
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private SchedulingExecutionContext() {
    }

    public static void install(Long id, PlnSchedulingRuleSetEntity rule, Runnable checkpoint) {
        if (CURRENT.get() != null) throw new IllegalStateException("不能嵌套受控排产运行");
        CURRENT.set(new State(id, rule, checkpoint));
    }

    public static void check() {
        var state = CURRENT.get();
        if (state != null) state.checkpoint().run();
    }

    public static Long executionId() {
        var state = CURRENT.get();
        return state == null ? null : state.id();
    }

    public static PlnSchedulingRuleSetEntity rule() {
        var state = CURRENT.get();
        return state == null ? null : state.rule();
    }

    public static void clear() {
        CURRENT.remove();
    }

    private record State(Long id, PlnSchedulingRuleSetEntity rule, Runnable checkpoint) {
    }
}
