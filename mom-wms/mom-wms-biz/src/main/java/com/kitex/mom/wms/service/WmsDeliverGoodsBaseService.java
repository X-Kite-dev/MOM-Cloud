package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.wms.entity.WmsDeliverGoodsBaseEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;

import java.util.Map;

public interface WmsDeliverGoodsBaseService extends IService<WmsDeliverGoodsBaseEntity> {


    /**
     * 分页查询
     *
     * @param toMpPage            分页对象
     * @param wmsDeliverGoodsBase 发货单基础表
     */
    Page<WmsDeliverGoodsBaseEntity> getWmsDeliverGoodsBasePage(Page toMpPage, WmsDeliverGoodsBaseEntity wmsDeliverGoodsBase);

    Map<String, Long> getCount(WmsDeliverGoodsBaseEntity wmsDeliverGoodsBase);


    /**
     * 通过id查询发货单基础表
     *
     * @param id id
     * @return R
     */
    WmsDeliverGoodsBaseEntity getDeliverBaseById(Long id);

    /**
     * 新增发货单
     */
    Boolean saveDeliver(WmsDeliverGoodsBaseEntity wmsDeliverGoodsBase);

    /**
     * 编辑发货单
     */
    Boolean editDeliver(WmsDeliverGoodsBaseEntity wmsDeliverGoodsBase);

    /**
     * 发货单关闭
     */
    Boolean close(Long id);

    /**
     * 根据出库单更新发货数据
     *
     * @param bill 出库单 主表
     */
    Boolean updateDeliverByBill(WmsOutBillEntity bill);

    /**
     * 创建销售通知单
     */
    Boolean create(Long id);

    /**
     * 撤回销售通知单
     */
    Boolean retract(Long id);

}