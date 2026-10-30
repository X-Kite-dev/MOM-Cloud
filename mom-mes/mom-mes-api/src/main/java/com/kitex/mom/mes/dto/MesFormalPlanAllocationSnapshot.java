package com.kitex.mom.mes.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * MES接收时生成的逐合同明细占量事实；服务端冻结，原接单载荷不增加字段。
 */
public record MesFormalPlanAllocationSnapshot(Integer schemaVersion, String taskId, String taskCode,
                                              String requestId, String requestFingerprint, String orderNo,
                                              String contractNo, Integer contractVersion,
                                              String contractFingerprint, String sourceDemandNo, String productCode,
                                              String unitCode,
                                              BigDecimal orderQuantity, BigDecimal releasedQuantityBaseline,
                                              List<Line> lines) {

    public MesFormalPlanAllocationSnapshot {
        if (lines != null) lines = List.copyOf(lines);
    }

    public record Line(String lineId, String lineNo, String fingerprint, BigDecimal quantity) {
    }
}
