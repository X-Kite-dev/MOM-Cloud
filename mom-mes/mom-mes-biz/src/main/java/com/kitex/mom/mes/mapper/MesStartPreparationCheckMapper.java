package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesStartPreparationCheck;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 快照永久请求串行化；事务锁不承担真实派工开工锁语义。
 */
@Mapper
public interface MesStartPreparationCheckMapper extends BaseMapper<MesStartPreparationCheck> {
    /**
     * 只投影当前页每张派工的最近一次本人核验。时间相同时以快照主键稳定排序，
     * 不加载完整证据；打开详情仍须走原请求回执重新核权。
     */
    @Select("""
            <script>
            SELECT DISTINCT ON (arrange_id) arrange_id, arrange_code, task_code, id, request_id, status, observed_at
            FROM mom_mes.mes_start_preparation_check
            WHERE tenant_id = #{tenantId} AND factory_code = #{factoryCode}
              AND operator_id = #{operatorId} AND del_flag = '0' AND arrange_id IN
              <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY arrange_id, observed_at DESC, id DESC
            </script>
            """)
    List<MesStartPreparationCheck> latestForPage(@Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode,
                                                @Param("operatorId") Long operatorId, @Param("ids") List<Long> ids);

    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    Integer lockRequest(@Param("key") String key);
}
