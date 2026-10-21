package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.plan.entity.PlnIssueCallbackReceiptEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PlnIssueCallbackReceiptMapper extends MomBaseMapper<PlnIssueCallbackReceiptEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('PLN_ISSUE_CALLBACK:', #{requestId}), 0))")
    void lockRequest(@Param("requestId") String requestId);
}
