package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesTaskInWmsCommandEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 原号跨工厂先读永久原件，由服务再核当前本人、工厂和完整原文。
 */
@Mapper
public interface MesTaskInWmsCommandMapper extends MomBaseMapper<MesTaskInWmsCommandEntity> {
    /**
     * 只核新命令目录，不把发送边界称为完整跨域冻结就绪。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT \"mom_mes\".mom_in_wms_command_ready_v1()")
    Boolean installed();

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT * FROM \"mom_mes\".mes_task_in_wms_command WHERE tenant_id=#{tenant} AND request_id=#{request}")
    MesTaskInWmsCommandEntity original(@Param("tenant") Long tenant, @Param("request") String request);

    /**
     * 与原inWms业务共用锁域；登记/取消不等待正在执行的业务事务。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT pg_try_advisory_xact_lock(hashtextextended(CONCAT('MES_TASK_IN_WMS:',#{request}),0))")
    Boolean tryRequest(@Param("request") String request);

    /**
     * 首次发送标记必须先独立提交，不能随入库主事务回滚消失。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Update("UPDATE \"mom_mes\".mes_task_in_wms_command SET status=#{next},update_time=now() "
            + "WHERE id=#{id} AND tenant_id=#{tenant} AND status=#{expected}")
    int transition(@Param("id") Long id, @Param("tenant") Long tenant,
                   @Param("expected") String expected, @Param("next") String next);
}
