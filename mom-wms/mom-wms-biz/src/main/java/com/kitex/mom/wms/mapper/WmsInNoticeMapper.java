package com.kitex.mom.wms.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WmsInNoticeMapper extends MomBaseMapper<WmsInNoticeEntity> {
    @Select("SELECT id FROM wms_in_notice WHERE id = #{id} FOR UPDATE")
    Long lockNotice(@Param("id") Long id);

    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(CONCAT('WMS_IN_NOTICE:', #{requestId}), 0))")
    int lockSourceRequest(@Param("requestId") String requestId);
}
