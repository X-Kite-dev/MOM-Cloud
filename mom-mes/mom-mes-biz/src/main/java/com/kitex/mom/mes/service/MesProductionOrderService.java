package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesProductionOrderDTO;
import com.kitex.mom.mes.dto.MesTaskReceiveDTO;
import com.kitex.mom.mes.entity.MesProductionOrderEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;

public interface MesProductionOrderService extends IService<MesProductionOrderEntity> {
    Boolean schedule(MesProductionOrderDTO query);

    /** 以正式合同为边界幂等登记 MES 生产订单主记录。 */
    MesProductionOrderEntity recordReceivedOrder(MesTaskReceiveDTO receive, MesTaskEntity task);
}
