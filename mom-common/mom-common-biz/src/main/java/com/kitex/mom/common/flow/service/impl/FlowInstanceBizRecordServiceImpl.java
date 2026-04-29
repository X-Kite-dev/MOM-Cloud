package com.kitex.mom.common.flow.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.common.flow.entity.FlowInstanceBizRecordEntity;
import com.kitex.mom.common.flow.mapper.FlowInstanceBizRecordMapper;
import com.kitex.mom.common.flow.service.FlowInstanceBizRecordService;
import org.springframework.stereotype.Service;

/**
 * 流程实例与业务关联记录
 *
 * @author kitex
 * @date 2025-02-12 11:24:23
 */
@Service
public class FlowInstanceBizRecordServiceImpl extends ServiceImpl<FlowInstanceBizRecordMapper, FlowInstanceBizRecordEntity> implements FlowInstanceBizRecordService {
}