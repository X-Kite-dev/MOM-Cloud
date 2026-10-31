package com.kitex.mom.wms.util;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;

import java.math.BigDecimal;

/**
 * 单件库存只能整体移动或改变质量状态，不能拆成两个共享序列号的库存行。
 */
public final class WmsSerialStockGuard {

    private WmsSerialStockGuard() {
    }

    public static void requireWhole(WmsLiveStockEntity stock, BigDecimal quantity) {
        if (!Boolean.TRUE.equals(stock.getSerialManaged())) {
            return;
        }
        Double stored = stock.getMatNum();
        if (stored == null || !Double.isFinite(stored) || stored != 1d
                || quantity == null || quantity.compareTo(BigDecimal.ONE) != 0
                || StrUtil.isBlank(stock.getMatBarcode())) {
            throw new CheckedException("单件序列号库存只能按完整实物处理，请核对库存数量和条码");
        }
    }
}
