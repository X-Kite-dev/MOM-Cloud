package com.kitex.mom.wms.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.entity.WmsProductionReturnReceiptOperationEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 生产退料实收与差异处置履历。
 */
@Mapper
public interface WmsProductionReturnReceiptOperationMapper
        extends MomBaseMapper<WmsProductionReturnReceiptOperationEntity> {
}
