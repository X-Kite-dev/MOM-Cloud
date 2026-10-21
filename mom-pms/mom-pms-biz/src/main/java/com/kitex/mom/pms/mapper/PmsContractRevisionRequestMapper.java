package com.kitex.mom.pms.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.pms.entity.PmsContractRevisionRequestEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PmsContractRevisionRequestMapper extends MomBaseMapper<PmsContractRevisionRequestEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('PMS_CONTRACT_REVISION:', #{key}), 0))")
    void lockKey(@Param("key") String key);

    @Select("SELECT * FROM pms_contract_revision_request WHERE id=#{id} AND del_flag='0' FOR UPDATE")
    PmsContractRevisionRequestEntity lockById(@Param("id") Long id);
}
