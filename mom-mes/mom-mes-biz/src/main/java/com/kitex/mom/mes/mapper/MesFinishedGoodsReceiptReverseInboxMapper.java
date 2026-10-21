package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesFinishedGoodsReceiptReverseInboxEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 访问MES接收WMS成品红冲事件的永久Inbox。
 */
@Mapper
public interface MesFinishedGoodsReceiptReverseInboxMapper
        extends MomBaseMapper<MesFinishedGoodsReceiptReverseInboxEntity> {
}
