package com.kitex.mom.plan.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.oms.agent.entity.OmsAgentProjectOrderEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 精确项目订单归属，不允许同订单在同工厂重复生效。
 */
@Mapper
public interface OmsAgentProjectOrderMapper extends BaseMapper<OmsAgentProjectOrderEntity> {
}
