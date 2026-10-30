package com.kitex.mom.plan.service;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.plan.mapper.PlnWarehouseLifecycleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 计划在创建WMS/MES记录之前也可能已使用仓库，查询包含全部业务历史。
 */
@Service
@RequiredArgsConstructor
public class PlnWarehouseLifecycleService {

    private final PlnWarehouseLifecycleMapper mapper;

    public List<String> references(String warehouseCode) {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        if (tenant == null || Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
                || StrUtil.isBlank(factory) || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                || StrUtil.isBlank(warehouseCode)) {
            throw new CheckedException("仓库依赖查询必须提供仓库并处于精确租户/工厂上下文");
        }
        List<String> references = mapper.findReferences(tenant, factory, warehouseCode);
        if (references == null || references.stream().anyMatch(StrUtil::isBlank)) {
            throw new CheckedException("仓库依赖查询结果不确定，禁止删除");
        }
        return references;
    }
}
