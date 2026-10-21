package com.kitex.mom.pms.service;

import com.kitex.mom.pms.dto.PmsDeliveryCommitmentCancelDTO;
import com.kitex.mom.pms.dto.PmsDeliveryCommitmentCommandDTO;
import com.kitex.mom.pms.entity.PmsDeliveryCommitmentEntity;

import java.util.Collection;
import java.util.List;

public interface PmsDeliveryCommitmentService {
    List<PmsDeliveryCommitmentEntity> listByOrder(Long orderId);

    List<PmsDeliveryCommitmentEntity> listActiveByPlanNos(String factoryCode, Collection<String> planNos);

    PmsDeliveryCommitmentEntity replace(PmsDeliveryCommitmentCommandDTO command);

    Boolean cancel(PmsDeliveryCommitmentCancelDTO command);
}
