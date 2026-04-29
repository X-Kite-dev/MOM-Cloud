package com.kitex.mom.mdm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mdm.entity.MdmUserFactoryModelBindEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户-工厂建模权限关联 Mapper 接口
 *
 * @author claude
 * @date 2025-07-30
 */
@Mapper
public interface MdmUserFactoryModelBindMapper extends BaseMapper<MdmUserFactoryModelBindEntity> {

}