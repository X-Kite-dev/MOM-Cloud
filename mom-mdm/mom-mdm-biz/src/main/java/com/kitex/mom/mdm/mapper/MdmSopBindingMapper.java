package com.kitex.mom.mdm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mdm.entity.MdmSopBindingEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 正式SOP节点绑定持久层。
 */
@Mapper
public interface MdmSopBindingMapper extends BaseMapper<MdmSopBindingEntity> {
}

