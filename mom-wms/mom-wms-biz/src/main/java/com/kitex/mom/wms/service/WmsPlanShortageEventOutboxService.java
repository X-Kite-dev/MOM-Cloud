package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.mom.wms.dto.WmsPlanShortageEventDTO;
import com.kitex.mom.wms.entity.WmsAllocationBillEntity;
import com.kitex.mom.wms.entity.WmsPlanShortageEventOutboxEntity;
import com.kitex.mom.wms.entity.WmsTransferVarianceCaseEntity;

import java.math.BigDecimal;

public interface WmsPlanShortageEventOutboxService {
    WmsPlanShortageEventOutboxEntity enqueueTransferCancelled(WmsAllocationBillEntity bill, BigDecimal shortageQuantity);

    WmsPlanShortageEventOutboxEntity enqueueTransferShortReceived(WmsAllocationBillEntity bill,
                                                                  WmsTransferVarianceCaseEntity variance,
                                                                  BigDecimal shortageQuantity);

    void supplySupplementForecast(Long varianceCaseId, WmsAllocationBillEntity supplementBill);

    Page<WmsPlanShortageEventOutboxEntity> page(SearchPage page, WmsPlanShortageEventDTO.Query query);

    WmsPlanShortageEventDTO.Detail detail(Long outboxId);

    WmsPlanShortageEventOutboxEntity supplyForecast(WmsPlanShortageEventDTO.ForecastCommand command);

    boolean retry(WmsPlanShortageEventDTO.ManualActionCommand command);

    int dispatchDue(int limit);
}
