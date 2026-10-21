package com.kitex.mom.mes.service;

import com.kitex.mom.mes.dto.MesQualityGateDecision;

import java.math.BigDecimal;

/**
 * 把QMS判定数量同步为MES报工和线边库存的可用/隔离数量。
 */
public interface MesQualityQuantityService {

    MesQualityGateDecision reconcile(String produceTaskCode, MesQualityGateDecision decision);

    MesQualityGateDecision reconcileRework(String produceTaskCode, Long qmsTaskId,
                                           BigDecimal usableNum, BigDecimal handledNum,
                                           boolean allQualified);
}
