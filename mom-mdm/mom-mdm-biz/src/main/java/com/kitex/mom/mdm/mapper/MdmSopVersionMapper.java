package com.kitex.mom.mdm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mdm.entity.MdmSopVersionEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 正式SOP版本持久层。
 */
@Mapper
public interface MdmSopVersionMapper extends BaseMapper<MdmSopVersionEntity> {
}

