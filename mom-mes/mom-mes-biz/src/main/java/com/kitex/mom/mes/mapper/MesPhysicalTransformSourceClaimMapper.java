package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesPhysicalTransformSourceClaimEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 永久占用子身份只由主账领域事务创建/取消，不提供独立CRUD控制器。 */
@Mapper
public interface MesPhysicalTransformSourceClaimMapper extends BaseMapper<MesPhysicalTransformSourceClaimEntity> {
    /** 必须释放主账记录的完整身份数，禁止漏行或删历史。 */
    @Update("UPDATE mes_physical_transform_source_claim SET status='RELEASED' WHERE hold_id=#{holdId} "
            + "AND tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND status='HELD' AND del_flag='0'")
    int release(@Param("holdId") Long holdId, @Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode);

    /** 正式执行后保留全部原身份，但释放活动唯一占用且不可按原命令重开。 */
    @Update("UPDATE mes_physical_transform_source_claim SET status='CONSUMED' WHERE hold_id=#{holdId} "
            + "AND tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND status='HELD' AND del_flag='0'")
    int consume(@Param("holdId") Long holdId, @Param("tenantId") Long tenantId,
                @Param("factoryCode") String factoryCode);
}
