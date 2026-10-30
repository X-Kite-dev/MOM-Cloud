package com.kitex.mom.plan.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.oms.agent.entity.OmsAgentProjectEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 项目范围版本持久化，外部变更只能通过异人审核状态机。
 */
@Mapper
public interface OmsAgentProjectMapper extends BaseMapper<OmsAgentProjectEntity> {
}
