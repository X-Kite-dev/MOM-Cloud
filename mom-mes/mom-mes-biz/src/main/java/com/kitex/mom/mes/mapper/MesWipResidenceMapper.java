package com.kitex.mom.mes.mapper;

import com.kitex.mom.mes.dto.MesWipResidenceQuery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface MesWipResidenceMapper {
    List<Map<String, Object>> snapshot(@Param("q") MesWipResidenceQuery query, @Param("tenant") Long tenant,
                                       @Param("factory") String factory, @Param("observedAt") LocalDateTime observedAt);
}
