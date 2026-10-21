package com.kitex.mom.tpm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.tpm.entity.TpmRepairNoticeEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TpmRepairNoticeMapper extends BaseMapper<TpmRepairNoticeEntity> {
    @Select("SELECT * FROM tpm_repair_notice WHERE id=#{id} AND tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND del_flag='0' FOR UPDATE")
    TpmRepairNoticeEntity lockOne(@Param("id") Long id, @Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode);
}
