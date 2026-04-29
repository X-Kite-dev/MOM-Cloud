package com.kitex.mom.plan.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.oms.entity.OmsOrderDetailsLogEntity;
import com.kitex.mom.plan.mapper.OmsOrderDetailsLogMapper;
import com.kitex.mom.plan.service.OmsOrderDetailsLogService;
import org.springframework.stereotype.Service;

/**
 * 订单交付信息-操作日志表
 *
 * @author kitex
 * @date 2025-04-18 17:35:48
 */
@Service
public class OmsOrderDetailsLogServiceImpl extends ServiceImpl<OmsOrderDetailsLogMapper, OmsOrderDetailsLogEntity> implements OmsOrderDetailsLogService {
}