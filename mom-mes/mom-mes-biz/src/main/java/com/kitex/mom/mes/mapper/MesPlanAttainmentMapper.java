package com.kitex.mom.mes.mapper;

import com.kitex.mom.mes.dto.MesPlanAttainmentDTO.Aggregate;
import com.kitex.mom.mes.dto.MesPlanAttainmentDTO.Coverage;
import com.kitex.mom.mes.dto.MesPlanAttainmentDTO.Fact;
import com.kitex.mom.mes.dto.MesPlanAttainmentDTO.Query;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SQL分类片段供汇总、分组及明细共同使用；组织参数始终显式绑定。
 */
@Mapper
public interface MesPlanAttainmentMapper {

    Aggregate summary(@Param("tenant") Long tenant, @Param("factory") String factory,
                      @Param("q") Query query, @Param("observed") LocalDateTime observed);

    List<Aggregate> groups(@Param("tenant") Long tenant, @Param("factory") String factory,
                           @Param("q") Query query, @Param("observed") LocalDateTime observed);

    Coverage coverage(@Param("tenant") Long tenant, @Param("factory") String factory,
                      @Param("q") Query query, @Param("observed") LocalDateTime observed);

    String detailTotal(@Param("tenant") Long tenant, @Param("factory") String factory,
                       @Param("q") Query query, @Param("observed") LocalDateTime observed);

    List<Fact> details(@Param("tenant") Long tenant, @Param("factory") String factory,
                       @Param("q") Query query, @Param("observed") LocalDateTime observed, @Param("offset") long offset);
}
