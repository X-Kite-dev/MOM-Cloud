package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesWarehouseFeedLeftDTO;
import com.kitex.mom.mes.dto.MesWarehouseUseDTO;
import com.kitex.mom.mes.entity.MesWarehouseFeedEntity;

import java.util.List;

public interface MesWarehouseFeedService extends IService<MesWarehouseFeedEntity> {

    /**
     * pad投料接口
     */
    Boolean saveFeed(MesWarehouseFeedEntity mesWarehouseFeed);

    /**
     * 退料
     *
     * @param mesWarehouseFeed
     */
    void feedBack(MesWarehouseFeedEntity mesWarehouseFeed);

    /**
     * 消耗
     */
    void use(MesWarehouseUseDTO useDTO);

    /**
     * 查询投料列表
     *
     * @return
     */
    List<MesWarehouseFeedLeftDTO> queryTaskFeedList(MesWarehouseFeedLeftDTO query);

    /**
     * 余料列表
     *
     * @param pageParam
     * @param query
     * @return
     */
    IPage<MesWarehouseFeedLeftDTO> queryLeftPage(Page<MesWarehouseFeedLeftDTO> pageParam, MesWarehouseFeedLeftDTO query);
}