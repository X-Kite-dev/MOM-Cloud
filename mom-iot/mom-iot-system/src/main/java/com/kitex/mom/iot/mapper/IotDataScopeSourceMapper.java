package com.kitex.mom.iot.mapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.iot.dto.IotDataScopeSource;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;
/** 唯一受控的未绑定租户历史入口。每次均显式限定设备、产品、NULL/本租户及NULL/本工厂。 */
@Mapper
public interface IotDataScopeSourceMapper {
    @InterceptorIgnore(tenantLine="true",dataPermission="true")
    @SelectProvider(type=IotDataScopeSourceSql.class,method="evidenceBytes")
    Long evidenceBytes(Map<String,Object> args);
    @InterceptorIgnore(tenantLine="true",dataPermission="true")
    @SelectProvider(type=IotDataScopeSourceSql.class,method="candidates")
    List<IotDataScopeSource> candidates(Map<String,Object> args);
    @InterceptorIgnore(tenantLine="true",dataPermission="true")
    @SelectProvider(type=IotDataScopeSourceSql.class,method="sources")
    List<IotDataScopeSource> sources(Map<String,Object> args);
    @InterceptorIgnore(tenantLine="true",dataPermission="true")
    @UpdateProvider(type=IotDataScopeSourceSql.class,method="approve")
    int approve(Map<String,Object> args);
}
