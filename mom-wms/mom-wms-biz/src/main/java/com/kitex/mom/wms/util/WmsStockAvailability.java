package com.kitex.mom.wms.util;

import cn.hutool.core.util.StrUtil;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;

import java.time.LocalDate;
import java.util.Set;

/**
 * WMS统一库存可用性判定：锁定、过期、待检和不合格库存不得预留或出库。
 */
public final class WmsStockAvailability {
    private static final Set<String> AVAILABLE_QUALITY_RESULTS = Set.of(
            "QUALIFIED", "EXEMPTION", "RELEASE", "RECEIVE", "CONCESSION");

    private WmsStockAvailability() {
    }

    public static boolean isUsable(WmsLiveStockEntity stock) {
        return WmsStockOwnership.isCompanyOwned(stock) && isPhysicallyUsable(stock);
    }

    public static boolean isPhysicallyUsable(WmsLiveStockEntity stock) {
        if (stock == null || stock.getStatus() == null || stock.getStatus() != 0) {
            return false;
        }
        if (stock.getMatValidEndDate() != null && stock.getMatValidEndDate().isBefore(LocalDate.now())) {
            return false;
        }
        String qualityStatus = StrUtil.trim(stock.getQualityStatus());
        if (StrUtil.isNotBlank(qualityStatus)
                && !Set.of("QUALIFIED", "CONCESSION", "RELEASED").contains(qualityStatus)) {
            return false;
        }
        String result = StrUtil.trim(stock.getQualityResult());
        if (result != null && AVAILABLE_QUALITY_RESULTS.contains(result)) {
            return true;
        }
        boolean qualityRequired = StrUtil.isNotBlank(stock.getIsQualityTest())
                && !"0".equals(stock.getIsQualityTest());
        // 未启用质检且没有任何质检结论的库存兼容历史数据；待检/作废/不合格均不可用。
        return !qualityRequired && StrUtil.isBlank(result);
    }
}
