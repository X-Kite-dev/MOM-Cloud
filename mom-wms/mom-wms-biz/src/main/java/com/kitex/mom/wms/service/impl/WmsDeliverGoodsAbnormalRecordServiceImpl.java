package com.kitex.mom.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.wms.entity.WmsDeliverGoodsAbnormalRecordEntity;
import com.kitex.mom.wms.enums.ShowHiddenStateEnum;
import com.kitex.mom.wms.mapper.WmsDeliverGoodsAbnormalRecordMapper;
import com.kitex.mom.wms.service.WmsDeliverGoodsAbnormalRecordService;
import org.springframework.stereotype.Service;

/**
 * 发货异常记录表
 *
 * @author kitex
 * @date 2025-01-13 09:26:52
 */
@Service
public class WmsDeliverGoodsAbnormalRecordServiceImpl extends ServiceImpl<WmsDeliverGoodsAbnormalRecordMapper, WmsDeliverGoodsAbnormalRecordEntity> implements WmsDeliverGoodsAbnormalRecordService {

    /**
     * 通过id填报发货异常记录表
     */
    @Override
    public Boolean report(WmsDeliverGoodsAbnormalRecordEntity wmsDeliverGoodsAbnormalRecord) {
        wmsDeliverGoodsAbnormalRecord.setState(ShowHiddenStateEnum.CLOSE.getCode());
        return this.updateById(wmsDeliverGoodsAbnormalRecord);
    }
}