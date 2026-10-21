package com.kitex.mom.mes.mapper.agent;

import com.kitex.mom.mes.agent.dto.MesProductionStatisticsDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 仅固定字段的全命中聚合SQL；无自由SQL或外部列名。 */
@Mapper
public interface MesProductionStatisticsMapper {
    Map<String, Object> taskSummary(@Param("tenantId") Long tenantId, @Param("q") MesProductionStatisticsDTO.Query query,
                                    @Param("start") LocalDateTime start, @Param("end") LocalDateTime end,
                                    @Param("observedAt") LocalDateTime observedAt);
    Map<String, Object> reportSummary(@Param("tenantId") Long tenantId, @Param("q") MesProductionStatisticsDTO.Query query,
                                      @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
    List<Map<String, Object>> quantities(@Param("tenantId") Long tenantId, @Param("q") MesProductionStatisticsDTO.Query query,
                                         @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
    long unattributed(@Param("tenantId") Long tenantId, @Param("q") MesProductionStatisticsDTO.Query query,
                      @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
    List<Map<String, Object>> ranking(@Param("tenantId") Long tenantId, @Param("q") MesProductionStatisticsDTO.Query query,
                                      @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
