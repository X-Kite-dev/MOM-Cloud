package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.tpm.entity.TpmRepairTaskEntity;

public interface TpmRepairTaskService extends IService<TpmRepairTaskEntity> {

    TpmRepairTaskEntity getTaskById(Long id);

    Boolean saveTask(TpmRepairTaskEntity tpmRepairTask);

    Boolean handleTask(TpmRepairTaskEntity tpmRepairTask);
}