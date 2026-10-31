package com.kitex.mom.mes.mapper;

import com.kitex.mom.mes.dto.MesProductionTimeDTO.Fact;
import com.kitex.mom.mes.dto.MesProductionTimeDTO.Query;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MesProductionTimeMapper {

    long orphanedSources(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("q") Query query);

    List<Fact> segments(@Param("tenant") Long tenant, @Param("factory") String factory,
                        @Param("q") Query query, @Param("limit") int limit);

    List<Fact> labor(@Param("tenant") Long tenant, @Param("factory") String factory,
                     @Param("q") Query query, @Param("limit") int limit);

    List<Fact> pauses(@Param("tenant") Long tenant, @Param("factory") String factory,
                      @Param("q") Query query, @Param("limit") int limit);

    List<Fact> workpieces(@Param("tenant") Long tenant, @Param("factory") String factory,
                          @Param("q") Query query, @Param("limit") int limit);
}
