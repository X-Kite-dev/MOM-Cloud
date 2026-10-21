package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.mom.tpm.dto.TpmPlanEventDTO;
import com.kitex.mom.tpm.entity.*;

import java.time.LocalDateTime;

public interface TpmPlanEventOutboxService {
    TpmPlanEventOutboxEntity enqueueRepairUnavailable(TpmRepairTaskEntity task);

    void markRepairRecovered(TpmRepairTaskEntity task);

    TpmPlanEventOutboxEntity enqueueMaintenanceUnavailable(TpmMaintenanceTaskEntity task,
                                                           LocalDateTime eventStartTime,
                                                           LocalDateTime expectedRecoveryTime);

    void markMaintenanceRecovered(TpmMaintenanceTaskEntity task);

    TpmPlanEventOutboxEntity enqueueScrapUnavailable(TpmScrapApplyEntity apply);

    TpmPlanEventOutboxEntity enqueueCheckUnavailable(TpmCheckTaskEntity task);

    void markCheckRecovered(TpmCheckTaskEntity task, String releaseEvidence);

    Page<TpmPlanEventOutboxEntity> page(SearchPage page, TpmPlanEventDTO.Query query);

    TpmPlanEventDTO.Detail detail(Long outboxId);

    TpmPlanEventOutboxEntity supplyForecast(TpmPlanEventDTO.ForecastCommand command);

    boolean retry(TpmPlanEventDTO.ManualActionCommand command);

    boolean cancel(TpmPlanEventDTO.ManualActionCommand command);

    int dispatchDue(int limit);
}
