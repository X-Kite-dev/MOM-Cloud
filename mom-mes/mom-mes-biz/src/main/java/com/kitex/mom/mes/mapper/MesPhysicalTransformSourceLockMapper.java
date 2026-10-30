package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.mes.entity.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 来源事务保护专用查询：先保护全部MES写域和原工单，再按各表精确主键取NOWAIT行锁。
 * 不继承持久实体Mapper，不提供通用写入、FIFO选择或跨组织补选。
 */
@Mapper
public interface MesPhysicalTransformSourceLockMapper {
    /**
     * 真正转换观察使用独占域；普通MES写者由数据库触发器取同域共享锁。
     */
    @Select("SELECT \"mom_mes\".mom_physical_source_scope_lock(#{tenant},#{factory})")
    Boolean tryScope(@Param("tenant") Long tenant, @Param("factory") String factory);

    /**
     * 与既有MES_NODE_TASK_LOCK_V1共用同一锁键，持域锁后不阻塞等待父工单。
     */
    @Select("SELECT pg_try_advisory_xact_lock(hashtextextended(#{key},0))")
    Boolean tryTask(@Param("key") String key);

    /**
     * 所有实际带租户工厂的MES表都须启用同一原守卫；新表漏挂不能冒充保护齐备。
     */
    @Select("SELECT \"mom_mes\".mom_physical_source_scope_ready()")
    Boolean scopeReady();

    /**
     * 真实数值容量只读；系统目录无组织列，只在本方法免业务过滤，不改变请求隔离。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT table_name::text AS table_name,column_name::text AS column_name,"
            + "data_type::text AS data_type,numeric_precision::text AS numeric_precision,numeric_scale::text AS numeric_scale "
            + "FROM information_schema.columns WHERE table_schema='mom_mes' AND "
            + "((table_name='mes_warehouse_live_stock' AND column_name='num') OR "
            + "(table_name='mes_warehouse_inout' AND column_name IN ('operate_num','stock_before_num','stock_after_num'))) "
            + "ORDER BY table_name,column_name")
    List<Map<String, String>> quantityColumns();

    /**
     * 父工单归属来自原报工，禁止用客户端taskCode决定锁目标。
     */
    @Select("SELECT * FROM mes_task WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND code=#{code} AND del_flag='0' "
            + "ORDER BY id LIMIT 2 FOR UPDATE NOWAIT")
    List<MesTaskEntity> tasks(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("code") String code);

    /**
     * 保存全实际工序图，2001仅检测超界；超过2000拒绝，不保留截断图。
     */
    @Select("SELECT * FROM mes_produce_task WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND task_code=#{task} "
            + "AND del_flag='0' ORDER BY id LIMIT 2001 FOR UPDATE NOWAIT")
    List<MesProduceTaskEntity> graph(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("task") String task);

    /**
     * 精确派工原行，由原报工的actual arrangeCode定位。
     */
    @Select("SELECT * FROM mes_task_arrange WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND code=#{code} "
            + "AND del_flag='0' ORDER BY id LIMIT 2 FOR UPDATE NOWAIT")
    List<MesTaskArrangeEntity> arranges(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("code") String code);

    /**
     * 精确原报工锁，观察和锁后身份仍须一致。
     */
    @Select("SELECT * FROM mes_task_report WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND id=#{id} "
            + "AND del_flag='0' FOR UPDATE NOWAIT")
    MesTaskReportEntity report(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    /**
     * 只锁实际来源ID，不能按物料/批次/FIFO替换另一来源。
     */
    @Select("SELECT * FROM mes_warehouse_live_stock WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND id=#{id} "
            + "AND del_flag='0' FOR UPDATE NOWAIT")
    MesWarehouseLiveStockEntity stock(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);
}
