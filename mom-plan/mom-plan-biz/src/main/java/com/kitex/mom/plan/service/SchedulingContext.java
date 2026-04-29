package com.kitex.mom.plan.service;

/**
 * 排产上下文：在同一请求线程内传递 warning 信息。
 * 使用方在 finally 块中调用 clear() 防止内存泄漏。
 */
public final class SchedulingContext {

    private static final ThreadLocal<String> WARNING = new ThreadLocal<>();

    private SchedulingContext() {}

    public static void setWarning(String message) {
        WARNING.set(message);
    }

    public static String getAndClearWarning() {
        String msg = WARNING.get();
        WARNING.remove();
        return msg;
    }

    public static void clear() {
        WARNING.remove();
    }
}
