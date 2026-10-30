package com.kitex.mom.wms.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WmsOutBillMapper extends MomBaseMapper<WmsOutBillEntity> {
    @Select("SELECT id FROM wms_out_bill WHERE id = #{id} FOR UPDATE")
    Long lockBill(@Param("id") Long id);
}
