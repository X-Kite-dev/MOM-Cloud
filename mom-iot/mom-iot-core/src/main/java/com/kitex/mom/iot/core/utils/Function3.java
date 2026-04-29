package com.kitex.mom.iot.core.utils;

/**
 * 接受三个参数的函数，返回一个结果
 *
 * @param <T1>
 * @param <T2>
 * @param <T3>
 * @param <R>
 */
@FunctionalInterface
public interface Function3<T1, T2, T3, R> {
    R apply(T1 var1, T2 var2, T3 var3);
}