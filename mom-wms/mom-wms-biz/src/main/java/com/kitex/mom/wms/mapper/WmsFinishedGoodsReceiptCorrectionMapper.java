package com.kitex.mom.wms.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.entity.WmsFinishedGoodsReceiptCorrectionEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 访问成品实收入库红冲申请主账。
 */
@Mapper
public interface WmsFinishedGoodsReceiptCorrectionMapper
        extends MomBaseMapper<WmsFinishedGoodsReceiptCorrectionEntity> {
}
