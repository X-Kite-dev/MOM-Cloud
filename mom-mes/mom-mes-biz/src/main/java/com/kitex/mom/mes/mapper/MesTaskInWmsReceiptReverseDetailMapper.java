package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesTaskInWmsReceiptReverseDetailEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 访问MES成品入库报工来源的不可变反向明细。
 */
@Mapper
public interface MesTaskInWmsReceiptReverseDetailMapper
        extends MomBaseMapper<MesTaskInWmsReceiptReverseDetailEntity> {
}
