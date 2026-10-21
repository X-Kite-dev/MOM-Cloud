package com.kitex.mom.plan.service.impl;

import cn.hutool.json.JSONUtil;
import com.kitex.mom.oms.entity.OmsOrderDetailsEntity;

import java.util.Arrays;

/**
 * OMS变更评审跨提交/复核冻结同一订单行核心事实，不把状态流转中的changeSign当作业务变化。
 */
final class OmsOrderLineEvidence {
    private OmsOrderLineEvidence() {
    }

    static String snapshot(OmsOrderDetailsEntity row) {
        return JSONUtil.toJsonStr(Arrays.asList(row.getId(), row.getOrderCode(), row.getFactoryCode(),
                row.getProductCode(), row.getProductName(), row.getProductSpec(), row.getProductUnit(),
                row.getDeliverTime(), row.getDeliverNum(), row.getDeliveredNum()));
    }
}
