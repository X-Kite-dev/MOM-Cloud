package com.kitex.mom.qms.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.qms.entity.QmsCapaMesRetestReopenReceiptEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * CAPA复开命令的永久幂等回执。
 */
@Mapper
public interface QmsCapaMesRetestReopenReceiptMapper extends MomBaseMapper<QmsCapaMesRetestReopenReceiptEntity> {
}
