package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.MesChangePointHandleEntity;
import com.kitex.mom.mes.entity.MesChangePointOperationEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;

import java.util.List;


/**
 * 变化点处理
 *
 * @author kitex
 * @since 2025-05-26
 */
public interface MesChangePointHandleService extends IService<MesChangePointHandleEntity> {

    /**
     * 分页查询
     *
     * @param pageParam
     * @param query
     * @return
     */
    IPage<MesChangePointHandleEntity> queryPage(Page<MesChangePointHandleEntity> pageParam, MesChangePointHandleEntity query);

    /**
     * 通知
     *
     * @param mesChangePointHandle
     */
    MesChangePointHandleEntity trigger(ChangePointTriggerCommand command);

    /**
     * 处理
     *
     * @param mesChangePointHandle
     */
    void accept(ChangePointAcceptCommand command);

    void handle(ChangePointHandleCommand command);

    void verify(ChangePointVerifyCommand command);

    void cancel(ChangePointCancelCommand command);

    void retryInspection(Long id);

    List<MesChangePointOperationEntity> operations(Long id);

    void assertStartAllowed(MesTaskArrangeEntity arrange);

    void assertReportAllowed(MesTaskArrangeEntity arrange);

    void scanSla();
}
