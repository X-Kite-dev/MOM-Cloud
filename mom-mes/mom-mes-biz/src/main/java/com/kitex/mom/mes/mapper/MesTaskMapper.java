package com.kitex.mom.mes.mapper;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.mom.mes.dto.MesTaskDTO;
import com.kitex.mom.mes.entity.MesTaskEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工单获取
 *
 * @author kitex
 * @since 2025-05-06
 */
@Mapper
public interface MesTaskMapper extends BaseMapper<MesTaskEntity> {
    default MesTaskDTO getByTaskCode(String taskCode) {
        MesTaskEntity mesTask = selectOne(Wrappers.<MesTaskEntity>lambdaQuery().eq(MesTaskEntity::getCode, taskCode));
        MesTaskDTO mesTaskDTO = new MesTaskDTO();
        BeanUtil.copyProperties(mesTask, mesTaskDTO);
        return mesTaskDTO;
    }
}
