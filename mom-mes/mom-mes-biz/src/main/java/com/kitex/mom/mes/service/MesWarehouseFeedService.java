package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesWarehouseFeedLeftDTO;
import com.kitex.mom.mes.dto.MesWarehouseUseDTO;
import com.kitex.mom.mes.entity.MesWarehouseFeedEntity;

import java.util.List;

/** 投料及实际耗用领域服务；写动作分别遵循原库存出生或原生产事务，不提供余额裸改入口。 */
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
     * 在原合格报工事务内精确耗用实际投料，保存原GOOD逐来源分配及必要回读。
     *
     * <p>只允许当前报告的GOOD数量，不接组合总量；调用者须保留原父工单锁和完整生产门。
     * 不再次出库，不改变原不良耗用、倒冲或冲销协议。</p>
     *
     * @param useDTO 当前真实GOOD报告及原派工快照，不能由外部接口直接指示余额变动
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
