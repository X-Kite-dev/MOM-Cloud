package com.kitex.mom.wms.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 在数据库中原子分配单件身份流水号，与批次流水号相互独立。 */
@Mapper
public interface WmsSerialCodeSequenceMapper {
    @Select("SELECT nextval('mom_wms.wms_serial_code_seq')")
    Long nextValue();
}
