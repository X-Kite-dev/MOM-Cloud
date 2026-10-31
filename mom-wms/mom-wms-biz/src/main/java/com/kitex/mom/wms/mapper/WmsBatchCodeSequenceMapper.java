package com.kitex.mom.wms.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 在数据库中原子分配批次流水号，不受事务回滚或模板修改影响。 */
@Mapper
public interface WmsBatchCodeSequenceMapper {
    @Select("SELECT nextval('mom_wms.wms_batch_code_seq')")
    Long nextValue();
}
