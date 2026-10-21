package com.kitex.mom.iot.mapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.iot.entity.IotMesOutbox;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper
public interface IotMesOutboxMapper extends BaseMapper<IotMesOutbox> {
    /** 调度发现只返回路由标识，载荷与写入仍按明确租户/工厂隔离。 */
    @InterceptorIgnore(tenantLine="true", dataPermission="true")
    @Select("SELECT o.id,o.tenant_id,o.factory_code FROM iot_mes_outbox o WHERE o.del_flag='0' AND "
        + "((o.status IN ('PENDING','RETRY') AND o.next_attempt_time<=now()) OR (o.status='SENDING' AND o.lease_until<=now())) "
        + "AND NOT EXISTS (SELECT 1 FROM iot_mes_outbox p WHERE p.tenant_id=o.tenant_id AND p.factory_code=o.factory_code "
        + "AND p.device_id=o.device_id AND p.source_id<o.source_id AND p.status<>'DELIVERED') "
        + "ORDER BY COALESCE(o.next_attempt_time,o.lease_until),o.id LIMIT 100")
    List<IotMesOutbox> due();
}
