package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesMaterialReceiptEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MesMaterialReceiptMapper extends MomBaseMapper<MesMaterialReceiptEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('MES-RECEIPT-SOURCE:', #{sourceId}), 0))")
    void lockSource(@Param("sourceId") Long sourceId);
}
