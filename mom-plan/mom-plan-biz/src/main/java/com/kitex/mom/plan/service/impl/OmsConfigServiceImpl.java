package com.kitex.mom.plan.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.oms.entity.OmsConfigEntity;
import com.kitex.mom.plan.mapper.OmsConfigMapper;
import com.kitex.mom.plan.service.OmsConfigService;
import org.springframework.stereotype.Service;

/**
 * 订单配置表
 *
 * @author kitex
 * @date 2025-03-24 10:24:55
 */
@Service
public class OmsConfigServiceImpl extends ServiceImpl<OmsConfigMapper, OmsConfigEntity> implements OmsConfigService {
}