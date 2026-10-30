package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.entity.WmsMaterialMoveSiteEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WmsMaterialMoveSiteMapper extends MomBaseMapper<WmsMaterialMoveSiteEntity> {

    // 请求回执不是库存行。服务从实际上下文传入租户和工厂，SQL在两处均显式限定范围。
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Insert("INSERT INTO mom_wms.wms_material_move_request "
            + "(tenant_id, factory_code, request_id, request_fingerprint, bill_no) "
            + "VALUES (#{tenantId}, #{factoryCode}, #{requestId}, #{fingerprint}, #{billNo}) "
            + "ON CONFLICT DO NOTHING")
    int reserveMobileRequest(@Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode,
                             @Param("requestId") String requestId, @Param("fingerprint") String fingerprint,
                             @Param("billNo") String billNo);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT request_fingerprint FROM mom_wms.wms_material_move_request "
            + "WHERE tenant_id = #{tenantId} AND factory_code = #{factoryCode} AND request_id = #{requestId}")
    String findMobileRequestFingerprint(@Param("tenantId") Long tenantId,
                                        @Param("factoryCode") String factoryCode,
                                        @Param("requestId") String requestId);
}
