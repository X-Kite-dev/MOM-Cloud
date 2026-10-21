package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.plan.entity.PlanMrpInventoryPeggingEntity;

import java.util.List;

public interface PlanMrpInventoryPeggingService extends IService<PlanMrpInventoryPeggingEntity> {
    List<PlanMrpInventoryPeggingEntity> listByMrpId(Long mrpId);

    void markReserved(Long mrpId, String reservationNo);

    void markPlanned(Long mrpId);

    void synchronizeStatus(Long mrpId, String reservationStatus);
}
