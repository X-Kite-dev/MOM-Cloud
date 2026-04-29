package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.entity.MesWipTimeoutHandleEntity;

public interface MesWipTimeoutHandleService extends IService<MesWipTimeoutHandleEntity> {
    IPage<MesWipTimeoutHandleEntity> queryPage(Page<MesWipTimeoutHandleEntity> page, MesWipTimeoutHandleEntity query);
    void handle(MesWipTimeoutHandleEntity handle);
}
