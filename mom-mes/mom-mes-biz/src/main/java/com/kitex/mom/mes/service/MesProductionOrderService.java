package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesProductionOrderDTO;
import com.kitex.mom.mes.entity.MesProductionOrderEntity;

public interface MesProductionOrderService extends IService<MesProductionOrderEntity> {
    Boolean schedule(MesProductionOrderDTO query);
}
