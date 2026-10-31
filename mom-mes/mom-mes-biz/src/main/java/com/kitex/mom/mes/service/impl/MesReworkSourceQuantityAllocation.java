package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.entity.MesApsBatchAllocationEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 仅分摊本次返工完工增量，保留各来源已经归集的数量及剩余额度。 */
final class MesReworkSourceQuantityAllocation {
    private MesReworkSourceQuantityAllocation() {}

    static List<BigDecimal> increments(
            List<MesApsBatchAllocationEntity> sources, BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new CheckedException("返工完工来源分摊量必须为正");
        }
        List<BigDecimal> capacities = new ArrayList<>();
        List<BigDecimal> increments = new ArrayList<>();
        BigDecimal available = BigDecimal.ZERO;
        BigDecimal remainingWeight = BigDecimal.ZERO;
        for (var source : sources) {
            BigDecimal created =
                    Objects.requireNonNullElse(source.getReworkQuantity(), BigDecimal.ZERO);
            BigDecimal completed =
                    Objects.requireNonNullElse(
                            source.getReworkCompletedQuantity(), BigDecimal.ZERO);
            BigDecimal weight = source.getAllocatedQuantity();
            if (weight == null
                    || weight.signum() <= 0
                    || completed.signum() < 0
                    || created.compareTo(completed) < 0) {
                throw new CheckedException("返工来源已有数量或分配量无效，请先核对来源账");
            }
            BigDecimal capacity = created.subtract(completed);
            capacities.add(capacity);
            increments.add(BigDecimal.ZERO);
            available = available.add(capacity);
            if (capacity.signum() > 0) remainingWeight = remainingWeight.add(weight);
        }
        if (available.compareTo(quantity) < 0) {
            throw new CheckedException("返工完工量超过来源剩余返工额度");
        }

        BigDecimal remaining = quantity;
        for (int i = 0; i < sources.size() && remaining.signum() > 0; i++) {
            BigDecimal capacity = capacities.get(i);
            if (capacity.signum() == 0) continue;
            BigDecimal weight = sources.get(i).getAllocatedQuantity();
            BigDecimal share =
                    remaining
                            .multiply(weight)
                            .divide(remainingWeight, 8, RoundingMode.HALF_UP)
                            .min(capacity)
                            .min(remaining);
            increments.set(i, share);
            remaining = remaining.subtract(share);
            remainingWeight = remainingWeight.subtract(weight);
        }
        // 后部来源触及额度时，按同一稳定顺序把余量补入仍有额度的来源。
        for (int i = 0; i < sources.size() && remaining.signum() > 0; i++) {
            BigDecimal extra = capacities.get(i).subtract(increments.get(i)).min(remaining);
            increments.set(i, increments.get(i).add(extra));
            remaining = remaining.subtract(extra);
        }
        if (remaining.signum() != 0) {
            throw new CheckedException("返工完工来源分摊不守恒，已整体回滚");
        }
        return increments;
    }
}
