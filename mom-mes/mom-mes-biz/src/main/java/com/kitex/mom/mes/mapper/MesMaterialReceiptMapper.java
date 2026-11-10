package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesMaterialReceiptEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MesMaterialReceiptMapper extends MomBaseMapper<MesMaterialReceiptEntity> {
    /** 按 WMS 原出库行串行登记通知，返回整数以避免映射 PostgreSQL 的 void 类型。 */
    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(CONCAT('MES-RECEIPT-SOURCE:', #{sourceId}), 0))")
    Integer lockSource(@Param("sourceId") Long sourceId);
}
