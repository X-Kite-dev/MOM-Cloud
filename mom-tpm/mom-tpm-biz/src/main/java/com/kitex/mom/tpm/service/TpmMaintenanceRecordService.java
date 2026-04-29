package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.tpm.entity.TpmMaintenanceRecordEntity;

public interface TpmMaintenanceRecordService extends IService<TpmMaintenanceRecordEntity> {

    TpmMaintenanceRecordEntity getRecordById(Long id);
}