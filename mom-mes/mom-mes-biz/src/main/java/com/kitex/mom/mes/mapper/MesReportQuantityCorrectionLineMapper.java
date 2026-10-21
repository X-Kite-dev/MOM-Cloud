package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesReportQuantityCorrectionLineEntity;
import org.apache.ibatis.annotations.Mapper;

/** 聚合报工部分纠正分配行持久化。 */
@Mapper
public interface MesReportQuantityCorrectionLineMapper
        extends MomBaseMapper<MesReportQuantityCorrectionLineEntity> {
}
