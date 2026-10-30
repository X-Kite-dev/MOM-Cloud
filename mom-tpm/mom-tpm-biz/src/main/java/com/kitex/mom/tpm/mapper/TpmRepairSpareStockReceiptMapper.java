package com.kitex.mom.tpm.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.tpm.entity.TpmRepairSpareStockReceiptEntity;
import org.apache.ibatis.annotations.Mapper;

/** 维修备件库存不可变回执持久层。 */
@Mapper
public interface TpmRepairSpareStockReceiptMapper extends MomBaseMapper<TpmRepairSpareStockReceiptEntity> {
}
