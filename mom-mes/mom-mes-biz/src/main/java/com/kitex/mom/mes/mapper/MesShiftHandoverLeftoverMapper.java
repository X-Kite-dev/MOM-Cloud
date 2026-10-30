package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesShiftHandoverLeftover;
import org.apache.ibatis.annotations.Mapper;

/**
 * 遗留事项按当前租户/工厂隔离；永久动作回执由协调服务同事务落账。
 */
@Mapper
public interface MesShiftHandoverLeftoverMapper extends BaseMapper<MesShiftHandoverLeftover> {
}
