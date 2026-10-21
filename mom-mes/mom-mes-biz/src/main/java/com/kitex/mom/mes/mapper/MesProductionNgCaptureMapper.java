package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesProductionNgCaptureEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 实物采集永久账、实际目录和独立原号锁；不暴露通用历史库存化入口。 */
@Mapper
public interface MesProductionNgCaptureMapper extends MomBaseMapper<MesProductionNgCaptureEntity> {
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT \"mom_mes\".mom_production_ng_inventory_ready()")
    Boolean ready();

    /** 新写必须具备实际通道列及数据库准入保护，不影响原号恢复的业务语义。 */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT \"mom_mes\".mom_production_ng_channel_ready_v1()")
    Boolean channelReady();

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT pg_try_advisory_xact_lock(hashtextextended(#{key},0))")
    Boolean tryRequest(@Param("key") String key);

    /** 新NG解析后按双标识检查首次实物；必须先持全来源独占锁，数据库首次INSERT再次复核。 */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT \"mom_mes\".mom_ng_new_identity_available_286(#{tenant},#{factory},#{sourceId})")
    Boolean newIdentityAvailable(@Param("tenant") Long tenant, @Param("factory") String factory,
                                 @Param("sourceId") Long sourceId);
}
