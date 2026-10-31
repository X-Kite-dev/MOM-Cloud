package com.kitex.mom.mes.mapper;

import com.kitex.mom.mes.dto.MesWipAgingDTO.Aggregate;
import com.kitex.mom.mes.dto.MesWipAgingDTO.Coverage;
import com.kitex.mom.mes.dto.MesWipAgingDTO.Fact;
import com.kitex.mom.mes.dto.MesWipAgingDTO.Query;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 全量聚合与分页共享证据片段，每个请求显式传入组织、同一观察时点及阈值截止。
 */
@Mapper
public interface MesWipAgingMapper {

    Aggregate summary(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("q") Query query,
                      @Param("observed") LocalDateTime observed, @Param("cutoff") LocalDateTime cutoff);

    List<Aggregate> groups(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("q") Query query,
                           @Param("observed") LocalDateTime observed, @Param("cutoff") LocalDateTime cutoff);

    Coverage coverage(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("q") Query query,
                      @Param("observed") LocalDateTime observed, @Param("cutoff") LocalDateTime cutoff);

    String detailTotal(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("q") Query query,
                       @Param("observed") LocalDateTime observed, @Param("cutoff") LocalDateTime cutoff);

    List<Fact> details(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("q") Query query,
                       @Param("observed") LocalDateTime observed, @Param("cutoff") LocalDateTime cutoff, @Param("offset") long offset);
}
