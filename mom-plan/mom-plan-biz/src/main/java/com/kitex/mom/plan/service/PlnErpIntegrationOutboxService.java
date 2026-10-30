package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.plan.dto.PlnErpPostingEnvelope;
import com.kitex.mom.plan.dto.PlnErpReconciliationDTO;
import com.kitex.mom.plan.entity.PlnErpIntegrationOutboxEntity;

import java.util.List;

/**
 * ERP 投递账领域服务。
 */
public interface PlnErpIntegrationOutboxService extends IService<PlnErpIntegrationOutboxEntity> {
    PlnErpIntegrationOutboxEntity enqueue(PlnErpPostingEnvelope envelope);

    PlnErpIntegrationOutboxEntity retry(Long id, String reason);

    List<PlnErpIntegrationOutboxEntity> recent(int limit);

    /**
     * 汇总当前工厂的投递状态、阻断与待人工处置请求。
     */
    PlnErpReconciliationDTO reconcile(int limit);
}
