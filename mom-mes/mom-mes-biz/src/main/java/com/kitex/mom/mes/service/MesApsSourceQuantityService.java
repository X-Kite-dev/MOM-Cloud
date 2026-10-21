package com.kitex.mom.mes.service;

import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.enums.MesSourceQuantityKind;

import java.math.BigDecimal;

/**
 * 将MES执行偏差按APS执行批次来源账精确归集并幂等回写PLAN。
 */
public interface MesApsSourceQuantityService {
    void record(MesTaskEntity sourceTask, String requestId, MesSourceQuantityKind kind,
                BigDecimal quantityDelta, String sourceReference, String content);
}
