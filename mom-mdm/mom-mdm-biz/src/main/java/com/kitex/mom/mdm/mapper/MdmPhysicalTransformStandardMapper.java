package com.kitex.mom.mdm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mdm.entity.MdmPhysicalTransformStandardEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 工程永久版本目录；查询含历史，不因逻辑删除允许重用版本。
 */
@Mapper
public interface MdmPhysicalTransformStandardMapper extends BaseMapper<MdmPhysicalTransformStandardEntity> {
    /**
     * 先取真实工厂依赖域锁，关联MDM写入也使用同键；竞争原操作失败、不阻塞反向行锁。
     */
    @Select("SELECT pg_try_advisory_xact_lock(hashtextextended(#{key},0))")
    Boolean dependencyLock(@Param("key") String key);

    /**
     * 原版本精确锁；请求不能指定租户工厂越界。
     */
    @Select("SELECT * FROM mdm_physical_transform_standard WHERE tenant_id=#{tenantId} AND factory_code=#{factory} "
            + "AND id=#{id} FOR UPDATE NOWAIT")
    MdmPhysicalTransformStandardEntity locked(@Param("tenantId") Long tenantId, @Param("factory") String factory,
                                              @Param("id") Long id);

    /**
     * 所有状态均参与版本计数，不释放旧号。
     */
    @Select("SELECT coalesce(max(version_no),0) FROM mdm_physical_transform_standard "
            + "WHERE tenant_id=#{tenantId} AND factory_code=#{factory} AND standard_no=#{no}")
    Integer maxVersion(@Param("tenantId") Long tenantId, @Param("factory") String factory, @Param("no") String no);
}
