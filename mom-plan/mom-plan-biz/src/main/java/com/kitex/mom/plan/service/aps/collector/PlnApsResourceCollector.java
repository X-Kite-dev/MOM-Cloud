package com.kitex.mom.plan.service.aps.collector;

/** 单模块、只读取证采集器。 */
public interface PlnApsResourceCollector {

    /** 稳定模块代码，用于快照排序和失效比对。 */
    String module();

    /** 越小越先采集，保证规范化快照顺序稳定。 */
    int order();

    /** 当前工厂范围内采集证据；异常交由编排服务转成显式不可用问题。 */
    PlnApsResourceEvidence collect(PlnApsResourceCollectionContext context);
}
