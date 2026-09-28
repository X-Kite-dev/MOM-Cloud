package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesWarehouseStockFeedJudgeEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 线边库余料退料审批
 *
 * @author kitex
 * @since 2025-04-08
 */
@Mapper
public interface MesWarehouseStockFeedJudgeMapper extends MomBaseMapper<MesWarehouseStockFeedJudgeEntity> {

    /**
     * 对业务键和永久请求号加事务级串行锁。
     */
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('MES_MATERIAL_RETURN:', #{key}), 0))")
    void lockKey(@Param("key") String key);
}
