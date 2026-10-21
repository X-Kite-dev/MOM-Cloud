package com.kitex.mom.qms.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.qms.entity.QmsProcedureSourceWithdrawalReceiptEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface QmsProcedureSourceWithdrawalReceiptMapper
        extends MomBaseMapper<QmsProcedureSourceWithdrawalReceiptEntity> {

    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    void lockKey(@Param("key") String key);
}
