package com.kitex.mom.pms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.pms.entity.PmsBusinessReconciliationEntity;
import com.kitex.mom.pms.entity.PmsOrderEntity;

public interface PmsBusinessReconciliationService extends IService<PmsBusinessReconciliationEntity> {

    void saveByPmsOrder(PmsOrderEntity pmsOrderEntity);
}