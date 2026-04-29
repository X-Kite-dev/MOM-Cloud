package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.wms.entity.WmsDeliverGoodsBaseEntity;
import com.kitex.mom.wms.entity.WmsDeliverGoodsPlanBaseEntity;

import java.util.ArrayList;
import java.util.Map;

public interface WmsDeliverGoodsPlanBaseService extends IService<WmsDeliverGoodsPlanBaseEntity> {


    Page<WmsDeliverGoodsPlanBaseEntity> getWmsDeliverGoodsPlanBasePage(Page<WmsDeliverGoodsPlanBaseEntity> toMpPage, WmsDeliverGoodsPlanBaseEntity wmsDeliverGoodsPlanBase);

    Map<String, Long> getCount(WmsDeliverGoodsPlanBaseEntity wmsDeliverGoodsPlanBase);


    WmsDeliverGoodsPlanBaseEntity getPlanBaseById(Long id);

    /**
     * 新增发货计划表
     *
     * @param wmsDeliverGoodsPlanBase 发货计划表
     * @return R
     */
    Boolean saveDeliverPlan(WmsDeliverGoodsPlanBaseEntity wmsDeliverGoodsPlanBase);


    /**
     * 修改发货计划表
     *
     * @param wmsDeliverGoodsPlanBase 发货计划表
     * @return R
     */
    Boolean updatePlanById(WmsDeliverGoodsPlanBaseEntity wmsDeliverGoodsPlanBase);


    /**
     * 通过id删除发货计划表
     *
     * @param idList id列表
     * @return R
     */
    Boolean removePlanById(ArrayList<Long> idList);

    /**
     * 通过发货计划号关闭计划
     */
    Boolean closePlan(String deliverPlanCode);

    /**
     * 通过发货计划号强制完成计划
     */
    Boolean completePlan(String deliverPlanCode);

    /**
     * 生成发货单
     */
    Boolean createDeliver(WmsDeliverGoodsBaseEntity deliverGoodsBase);


}