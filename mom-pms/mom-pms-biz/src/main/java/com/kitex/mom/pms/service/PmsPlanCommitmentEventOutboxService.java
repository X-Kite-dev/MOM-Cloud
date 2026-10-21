package com.kitex.mom.pms.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.mom.pms.dto.PmsPlanCommitmentEventDTO;
import com.kitex.mom.pms.entity.PmsDeliveryCommitmentEntity;
import com.kitex.mom.pms.entity.PmsOrderChangeLineEntity;
import com.kitex.mom.pms.entity.PmsOrderChangeRequestEntity;
import com.kitex.mom.pms.entity.PmsPlanCommitmentEventOutboxEntity;

public interface PmsPlanCommitmentEventOutboxService {
    void enqueueActivated(PmsDeliveryCommitmentEntity commitment);

    void enqueueCancelled(PmsDeliveryCommitmentEntity commitment);

    /**
     * 订单剩余供给取消后建立待补预测的PLAN缺料事件；不伪造替代到料时间。
     */
    void enqueueOrderRemainingCancelled(PmsOrderChangeRequestEntity request, PmsOrderChangeLineEntity line);

    /**
     * 订单数量、交期或供应商变更导致原供应事实失效时建立PLAN缺料事件。
     */
    void enqueueOrderAmended(PmsOrderChangeRequestEntity request, PmsOrderChangeLineEntity line);

    Page<PmsPlanCommitmentEventOutboxEntity> page(SearchPage page, PmsPlanCommitmentEventDTO.Query query);

    PmsPlanCommitmentEventDTO.Detail detail(Long outboxId);

    PmsPlanCommitmentEventOutboxEntity supplyForecast(PmsPlanCommitmentEventDTO.ForecastCommand command);

    boolean retry(PmsPlanCommitmentEventDTO.ManualActionCommand command);

    int dispatchDue(int limit);
}
