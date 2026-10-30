package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesIdentifierBindingGroupEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 永久换码区段持久层；历史派工包括逻辑删除行，不把旧NONE事实重新解释成新绑定。
 */
@Mapper
public interface MesIdentifierBindingGroupMapper extends BaseMapper<MesIdentifierBindingGroupEntity> {

    /**
     * 缺行也可争抢的事务级工单业务锁，正文采用有界、无分隔歧义的JSON键。
     */
    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    Integer lockTaskBusinessKey(@Param("key") String key);

    /**
     * 完整核对同工单历史快照，超过5000行由领域服务拒绝截断。
     */
    @Select("SELECT * FROM mes_task_arrange WHERE tenant_id = #{tenantId} "
            + "AND factory_code = #{factoryCode} AND task_code = #{taskCode} ORDER BY id LIMIT 5001")
    List<MesTaskArrangeEntity> selectTaskArrangementHistory(@Param("tenantId") Long tenantId,
                                                            @Param("factoryCode") String factoryCode,
                                                            @Param("taskCode") String taskCode);
}
