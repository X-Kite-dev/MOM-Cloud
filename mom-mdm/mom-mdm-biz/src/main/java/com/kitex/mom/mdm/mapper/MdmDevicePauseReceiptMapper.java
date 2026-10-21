package com.kitex.mom.mdm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mdm.entity.MdmDevicePauseReceipt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MdmDevicePauseReceiptMapper extends BaseMapper<MdmDevicePauseReceipt> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key},0))")
    void lockRequest(@Param("key") String key);
}
