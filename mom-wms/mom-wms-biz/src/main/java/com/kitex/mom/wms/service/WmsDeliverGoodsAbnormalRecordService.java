package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.wms.entity.WmsDeliverGoodsAbnormalRecordEntity;

public interface WmsDeliverGoodsAbnormalRecordService extends IService<WmsDeliverGoodsAbnormalRecordEntity> {

    /**
     * 通过id填报发货异常记录表
     */
    Boolean report(WmsDeliverGoodsAbnormalRecordEntity wmsDeliverGoodsAbnormalRecord);
}