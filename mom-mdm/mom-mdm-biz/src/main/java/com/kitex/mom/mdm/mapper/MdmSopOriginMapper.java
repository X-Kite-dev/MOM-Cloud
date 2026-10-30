package com.kitex.mom.mdm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mdm.entity.MdmSopOriginEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 编制来源仅通过统一SOP事务写入，不提供独立的通用增删改接口。
 */
@Mapper
public interface MdmSopOriginMapper extends BaseMapper<MdmSopOriginEntity> {
}
