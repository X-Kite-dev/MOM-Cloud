package com.kitex.mom.mdm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mdm.entity.MdmAgentProcessReceiptEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工艺建议正式转交的永久领域回执。
 */
@Mapper
public interface MdmAgentProcessReceiptMapper extends BaseMapper<MdmAgentProcessReceiptEntity> {
}
