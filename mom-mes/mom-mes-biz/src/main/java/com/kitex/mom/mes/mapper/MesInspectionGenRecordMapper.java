package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesInspectionGenRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.kitex.mom.mes.dto.MesInspectionRecordWorkbenchDTO.Query;
import java.util.List;

/**
 * 质检任务生成记录 Mapper 接口
 *
 * @author kitex
 * @since 2024-02-06
 */
@Mapper
public interface MesInspectionGenRecordMapper extends BaseMapper<MesInspectionGenRecordEntity> {
    Long workbenchCount(@Param("tenant") Long tenant,
                        @Param("factory") String factory,
                        @Param("q") Query query);
    List<MesInspectionGenRecordEntity> workbenchRows(@Param("tenant") Long tenant,
                        @Param("factory") String factory,
                        @Param("q") Query query,
                        @Param("offset") long offset,
                        @Param("limit") int limit);
    MesInspectionGenRecordEntity workbenchDetail(@Param("tenant") Long tenant,
                        @Param("factory") String factory,
                        @Param("id") Long id);

} 