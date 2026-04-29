package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.wms.dto.app.WmsAllocationBillAppDTO;
import com.kitex.mom.wms.entity.WmsInoutFlowEntity;

public interface WmsInoutFlowService extends IService<WmsInoutFlowEntity> {

    void inOutFlow(WmsAllocationBillAppDTO wmsAllocationBillAdd);
}