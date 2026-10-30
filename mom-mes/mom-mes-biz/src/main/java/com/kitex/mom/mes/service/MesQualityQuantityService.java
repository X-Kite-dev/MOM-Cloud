package com.kitex.mom.mes.service;

import com.kitex.mom.mes.dto.MesQualityGateDecision;

import java.math.BigDecimal;

/**
 * 把QMS判定数量同步为MES报工和线边库存的可用/隔离数量。
 */
public interface MesQualityQuantityService {

    MesQualityGateDecision reconcile(String produceTaskCode, MesQualityGateDecision decision);

    MesQualityGateDecision reconcileRework(
            String produceTaskCode,
            Long qmsTaskId,
            BigDecimal usableNum,
            BigDecimal handledNum,
            boolean allQualified);

    /**
     * 在根业务锁内重新核候选冻结依据，供正式应用事务调用；不接受客户端判定对象。
     */
    MesQualityGateDecision reconcileEvent(
            String produceTaskCode, Long recordId, Long eventId, java.util.Date appliedAt);
}
