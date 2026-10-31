package com.kitex.mom.qms.dto;

/** 生产检验持久样本的内部聚合投影，不属于外部检验事件协议。 */
public record QmsInspectionSamples(
        Long detailId, long total, long unqualified, long undetermined) {}
