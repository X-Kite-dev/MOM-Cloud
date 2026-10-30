package com.kitex.mom.wms.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 通知单
 */
@Mapper
public interface WmsOutNoticeMapper extends MomBaseMapper<WmsOutNoticeEntity> {
    @Select("SELECT id FROM wms_out_notice WHERE id = #{id} FOR UPDATE")
    Long lockNotice(@Param("id") Long id);
}
