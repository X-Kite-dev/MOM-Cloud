package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.WipTimeoutCancelCommand;
import com.kitex.mom.mes.dto.WipTimeoutHandleCommand;
import com.kitex.mom.mes.dto.WipTimeoutVerifyCommand;
import com.kitex.mom.mes.entity.MesWipTimeoutHandleEntity;
import com.kitex.mom.mes.entity.MesWipTimeoutOperationEntity;

import java.util.List;

public interface MesWipTimeoutHandleService extends IService<MesWipTimeoutHandleEntity> {
    IPage<MesWipTimeoutHandleEntity> queryPage(Page<MesWipTimeoutHandleEntity> page, MesWipTimeoutHandleEntity query);

    void handle(WipTimeoutHandleCommand handle);

    void verify(WipTimeoutVerifyCommand command);

    void cancel(WipTimeoutCancelCommand command);

    List<MesWipTimeoutOperationEntity> operations(Long id);

    void scanNow();
}
