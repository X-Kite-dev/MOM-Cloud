package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.oms.dto.OmsOrderLifeCycleDTO;
import com.kitex.mom.plan.entity.PlanMatMrpEntity;

import java.util.List;

public interface PlanMatMrpService extends IService<PlanMatMrpEntity> {

    /**
     * 下发任务
     *
     * @param ids MRP运算记录表ids
     * @return R
     */
    Boolean issueByIds(Long[] ids);

    /**
     * 完成数量处理
     *
     * @param omsOrderLifeCycleDtoList 订单生命周期DTO
     * @return R
     */
    Boolean completeNumHandle(List<OmsOrderLifeCycleDTO> omsOrderLifeCycleDtoList);
}