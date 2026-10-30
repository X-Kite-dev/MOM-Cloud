package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesWarehouseFeedCommandEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 永久原号跨工厂唯一；显式租户查询不被当前工厂过滤成虚假ABSENT。
 */
@Mapper
public interface MesWarehouseFeedCommandMapper extends MomBaseMapper<MesWarehouseFeedCommandEntity> {
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT \"mom_mes\".mom_feed_command_ready_261()")
    Boolean ready();

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT pg_try_advisory_xact_lock(hashtextextended(#{key},0))")
    Boolean tryRequest(@Param("key") String key);

    /**
     * 同租户原号只有一个永久终态；调用者必须再次核真实工厂和本人。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT * FROM \"mom_mes\".mes_warehouse_feed_command WHERE tenant_id=#{tenant} AND request_id=#{requestId} LIMIT 2")
    List<MesWarehouseFeedCommandEntity> original(@Param("tenant") Long tenant, @Param("requestId") String requestId);
}
