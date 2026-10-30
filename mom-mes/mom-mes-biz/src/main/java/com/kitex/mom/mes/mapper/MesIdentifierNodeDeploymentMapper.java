package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;

/**
 * 只读PostgreSQL系统目录，不查业务行、不执行DDL或nextval。
 *
 * <p>目录没有tenant_id/factory_code，故只在此条元数据查询跳过租户/数据权限改写；
 * 调用前由领域组件核实际实名组织，绝不改变请求上下文的skip标志。</p>
 */
@Mapper
public interface MesIdentifierNodeDeploymentMapper {

    /**
     * 返回精确缺项JSON数组；只有数组确实为空才证明结构前置匹配。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    String missingControls();
}
