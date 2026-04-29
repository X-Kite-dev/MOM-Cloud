package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.tpm.entity.TpmMaintenanceTaskEntity;

public interface TpmMaintenanceTaskService extends IService<TpmMaintenanceTaskEntity> {

    /**
     * 通过id查询保养任务表
     */
    TpmMaintenanceTaskEntity getByTaskId(Long id);

    /**
     * 处理保养任务
     */
    Boolean handle(TpmMaintenanceTaskEntity tpmMaintenanceTask);
}