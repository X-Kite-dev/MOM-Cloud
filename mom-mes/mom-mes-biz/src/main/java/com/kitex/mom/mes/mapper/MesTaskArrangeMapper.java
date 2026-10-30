package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.dto.MesDispatchWorkbenchDTO;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 生产派工
 *
 * @author kitex
 * @since 2025-05-06
 */
@Mapper
public interface MesTaskArrangeMapper extends BaseMapper<MesTaskArrangeEntity> {

    /**
     * 只聚合当前分页工单的真实工序任务，避免跨组织计数及逐单查询。
     */
    @Select({"<script>",
            "SELECT task_code AS \"taskCode\", count(*) AS \"processTaskCount\"",
            "FROM mom_mes.mes_produce_task",
            "WHERE tenant_id = #{tenantId} AND factory_code = #{factoryCode} AND del_flag = '0'",
            "AND task_code IN <foreach collection='taskCodes' item='code' open='(' separator=',' close=')'>#{code}</foreach>",
            "GROUP BY task_code",
            "</script>"})
    List<MesDispatchWorkbenchDTO.ProcessCount> countProcessTasks(@Param("tenantId") Long tenantId,
                                                                 @Param("factoryCode") String factoryCode,
                                                                 @Param("taskCodes") List<String> taskCodes);

    /**
     * 历史派工与活动派工分别计数；关闭/完工的派工不冒充当前可执行资源。
     */
    @Select({"<script>",
            "SELECT task_code AS \"taskCode\", count(*) AS \"dispatchCount\",",
            "count(*) FILTER (WHERE status NOT IN ('CLOSE', 'FINISH')) AS \"activeDispatchCount\",",
            "count(DISTINCT produce_task_code) FILTER (WHERE status NOT IN ('CLOSE', 'FINISH')) AS \"activeProcessCount\",",
            "max(dispatcher_time) AS \"latestDispatchTime\"",
            "FROM mom_mes.mes_task_arrange",
            "WHERE tenant_id = #{tenantId} AND factory_code = #{factoryCode} AND del_flag = '0'",
            "AND task_code IN <foreach collection='taskCodes' item='code' open='(' separator=',' close=')'>#{code}</foreach>",
            "GROUP BY task_code",
            "</script>"})
    List<MesDispatchWorkbenchDTO.DispatchSummary> summarizeDispatches(@Param("tenantId") Long tenantId,
                                                                      @Param("factoryCode") String factoryCode,
                                                                      @Param("taskCodes") List<String> taskCodes);
}
