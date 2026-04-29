package com.kitex.mom.tpm.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.tpm.dto.EquipmentRunDto;
import com.kitex.mom.tpm.entity.TpmEventRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TpmEventRecordMapper extends MomBaseMapper<TpmEventRecordEntity> {


    List<EquipmentRunDto> countDowntimeRankin(@Param("startTime") LocalDateTime startTime,
                                              @Param("endTime") LocalDateTime endTime,
                                              @Param("power_on") String powerOn,
                                              @Param("close") String close);
}