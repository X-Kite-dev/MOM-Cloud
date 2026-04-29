package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.tpm.entity.TpmRepairRecordEntity;

public interface TpmRepairRecordService extends IService<TpmRepairRecordEntity> {

    TpmRepairRecordEntity getRecordById(Long id);
}