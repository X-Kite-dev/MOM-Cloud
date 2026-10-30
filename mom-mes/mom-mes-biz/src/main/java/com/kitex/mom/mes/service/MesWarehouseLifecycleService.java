package com.kitex.mom.mes.service;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.mapper.MesWarehouseLifecycleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 只读查询当前租户/工厂的全部使用记录，包括软删历史。
 */
@Service
@RequiredArgsConstructor
public class MesWarehouseLifecycleService {

    private final MesWarehouseLifecycleMapper mapper;

    public List<String> references(String warehouseCode, Long warehouseId) {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        if (tenant == null || Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
                || StrUtil.isBlank(factory) || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                || StrUtil.isBlank(warehouseCode) || warehouseId == null) {
            throw new CheckedException("仓库依赖查询必须提供仓库并处于精确租户/工厂上下文");
        }
        List<String> references = mapper.findReferences(tenant, factory, warehouseCode, String.valueOf(warehouseId));
        if (references == null || references.stream().anyMatch(StrUtil::isBlank)) {
            throw new CheckedException("仓库依赖查询结果不确定，禁止删除");
        }
        return references;
    }
}
