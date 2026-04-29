package com.kitex.mom.tpm.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.tpm.dto.RankingDto;
import com.kitex.mom.tpm.entity.TpmSparePartsReplaceEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TpmSparePartsReplaceMapper extends MomBaseMapper<TpmSparePartsReplaceEntity> {


    /**
     * 查询时间范围内 备件更换 和 设备维修中的备件消耗
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     */
    List<RankingDto> sparePartsReplacement(@Param("startTime") LocalDateTime startTime,
                                           @Param("endTime") LocalDateTime endTime);
}