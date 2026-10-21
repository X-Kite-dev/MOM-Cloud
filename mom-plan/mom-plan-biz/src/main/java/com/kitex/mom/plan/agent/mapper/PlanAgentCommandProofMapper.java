package com.kitex.mom.plan.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.plan.agent.entity.PlanAgentCommandProofEntity;
import org.apache.ibatis.annotations.Mapper;

/** 实际租户/工厂隔离的永久命令证据账。 */
@Mapper
public interface PlanAgentCommandProofMapper extends BaseMapper<PlanAgentCommandProofEntity> {
}
