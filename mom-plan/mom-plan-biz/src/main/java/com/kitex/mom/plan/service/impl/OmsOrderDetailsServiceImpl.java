package com.kitex.mom.plan.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.oms.entity.OmsOrderDetailsEntity;
import com.kitex.mom.plan.mapper.OmsOrderDetailsMapper;
import com.kitex.mom.plan.service.OmsOrderDetailsService;
import org.springframework.stereotype.Service;

/**
 * 订单交付信息表
 *
 * @author kitex
 * @date 2025-01-07 14:38:56
 */
@Service
public class OmsOrderDetailsServiceImpl extends ServiceImpl<OmsOrderDetailsMapper, OmsOrderDetailsEntity> implements OmsOrderDetailsService {
}