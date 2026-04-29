package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.wms.entity.WmsDeliverGoodsBaseEntity;
import com.kitex.mom.wms.entity.WmsDeliverGoodsReturnEntity;
import com.kitex.mom.wms.entity.WmsInBillEntity;
import com.kitex.mom.wms.entity.WmsInNoticeDetailEntity;

import java.util.List;

public interface WmsDeliverGoodsReturnService extends IService<WmsDeliverGoodsReturnEntity> {

    /**
     * 根据id查询
     *
     * @param id id
     */
    WmsDeliverGoodsReturnEntity getReturnById(Long id);

    List<WmsInNoticeDetailEntity> getInNotice(String deliverCode);

    /**
     * 新增
     *
     * @param wmsDeliverGoodsReturn 主体
     */
    Boolean saveReturn(WmsDeliverGoodsReturnEntity wmsDeliverGoodsReturn);

    /**
     * 编辑
     *
     * @param wmsDeliverGoodsReturn 主体
     */
    Boolean updateReturnById(WmsDeliverGoodsReturnEntity wmsDeliverGoodsReturn);


    /**
     * 补发
     */
    Boolean reissue(WmsDeliverGoodsBaseEntity base);

    /**
     * 通过
     */
    Boolean pass(Long id);

    String createReissueCode(String deliverCode);

    /**
     * 根据入库单更新退货信息
     */
    void updateReturnByBill(WmsInBillEntity bill);


}