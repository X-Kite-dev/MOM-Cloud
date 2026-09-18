package com.kitex.mom.wms.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.entity.WmsAllocationBillEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WmsAllocationBillMapper extends MomBaseMapper<WmsAllocationBillEntity> {

    /** 将Agent调拨的幂等号与目标仓物料校验串行化，事务结束自动释放。 */
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('WMS_AGENT_TRANSFER:', #{lockKey}), 0))")
    void lockAgentTransfer(String lockKey);
}
