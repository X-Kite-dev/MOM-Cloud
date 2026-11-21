package com.kitex.mom.pms.service;

import com.kitex.mom.pms.dto.PmsDeliveryCommitmentCancelDTO;
import com.kitex.mom.pms.dto.PmsDeliveryCommitmentCommandDTO;
import com.kitex.mom.pms.entity.PmsDeliveryCommitmentEntity;

import java.util.Collection;
import java.util.List;

public interface PmsDeliveryCommitmentService {
    /** 查询整单承诺版本，供原采购订单入口使用。 */
    default List<PmsDeliveryCommitmentEntity> listByOrder(Long orderId) {
        return listByOrder(orderId, null);
    }

    /** 指定原订单行时，限定当前租户、工厂及父采购单，再加载各版的分批交期。 */
    List<PmsDeliveryCommitmentEntity> listByOrder(Long orderId, Long orderDetailId);

    List<PmsDeliveryCommitmentEntity> listActiveByPlanNos(String factoryCode, Collection<String> planNos);

    PmsDeliveryCommitmentEntity replace(PmsDeliveryCommitmentCommandDTO command);

    Boolean cancel(PmsDeliveryCommitmentCancelDTO command);
}
