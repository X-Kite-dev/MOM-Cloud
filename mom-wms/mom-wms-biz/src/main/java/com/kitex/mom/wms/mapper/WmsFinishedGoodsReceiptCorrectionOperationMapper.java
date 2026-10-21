package com.kitex.mom.wms.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.entity.WmsFinishedGoodsReceiptCorrectionOperationEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 访问成品实收入库红冲不可变操作履历。
 */
@Mapper
public interface WmsFinishedGoodsReceiptCorrectionOperationMapper
        extends MomBaseMapper<WmsFinishedGoodsReceiptCorrectionOperationEntity> {
}
