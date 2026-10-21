package com.kitex.mom.pms.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.pms.entity.PmsContractTerminationRequestEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PmsContractTerminationRequestMapper extends MomBaseMapper<PmsContractTerminationRequestEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('PMS_CONTRACT_TERMINATION:', #{key}), 0))")
    void lockKey(@Param("key") String key);

    @Select("SELECT * FROM pms_contract_termination_request WHERE id=#{id} AND del_flag='0' FOR UPDATE")
    PmsContractTerminationRequestEntity lockById(@Param("id") Long id);
}
