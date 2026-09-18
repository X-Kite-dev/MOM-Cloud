package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.entity.MesWrapEntity;
import com.kitex.mom.mes.dto.MesWrapRepackCommand;
import com.kitex.mom.mes.dto.MesWrapUnpackCommand;

import java.math.BigDecimal;

/**
 * 箱码管理
 *
 * @author kitex
 * @since 2025-03-25
 */
public interface MesWrapService extends IService<MesWrapEntity> {

    /**
     * @param mesWrap
     */
    String addData(MesWrapEntity mesWrap);

    /**
     * 分页查询
     *
     * @param pageParam
     * @param query
     * @return
     */
    IPage<MesWrapEntity> queryPage(Page<MesWrapEntity> pageParam, MesWrapEntity query);

    void unpack(MesWrapUnpackCommand command);

    String repack(MesWrapRepackCommand command);

    void registerQuantityPackage(MesWrapEntity wrap, Long reportId, String transferCode, BigDecimal quantity);

}
