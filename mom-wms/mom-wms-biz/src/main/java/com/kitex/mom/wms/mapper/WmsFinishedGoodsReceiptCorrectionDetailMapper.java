package com.kitex.mom.wms.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.entity.WmsFinishedGoodsReceiptCorrectionDetailEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 访问红冲申请时冻结的实时库存证据。
 */
@Mapper
public interface WmsFinishedGoodsReceiptCorrectionDetailMapper
        extends MomBaseMapper<WmsFinishedGoodsReceiptCorrectionDetailEntity> {
}
