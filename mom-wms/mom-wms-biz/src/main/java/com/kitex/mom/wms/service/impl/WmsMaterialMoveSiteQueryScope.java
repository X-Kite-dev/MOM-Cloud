package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.dto.WmsMaterialMoveSiteQueryDTO;
import com.kitex.mom.wms.entity.WmsMaterialMoveSiteEntity;

import java.util.Arrays;

/**
 * 库内移动列表和导出的同一查询范围，输入条件不得覆盖服务端组织范围。
 */
public final class WmsMaterialMoveSiteQueryScope {
    private WmsMaterialMoveSiteQueryScope() {
    }

    public static LambdaQueryWrapper<WmsMaterialMoveSiteEntity> build(WmsMaterialMoveSiteQueryDTO query, Long[] ids) {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        if (tenant == null || StrUtil.isBlank(factory)
                || Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())) {
            throw new CheckedException("库内移动查询必须具有租户和工厂上下文");
        }
        if (query == null) {
            throw new CheckedException("库内移动查询条件不能为空");
        }
        if (ids != null && Arrays.stream(ids).anyMatch(id -> id == null || id <= 0)) {
            throw new CheckedException("移库记录ID不正确");
        }
        LambdaQueryWrapper<WmsMaterialMoveSiteEntity> wrapper = new LambdaQueryWrapper<WmsMaterialMoveSiteEntity>()
                .eq(WmsMaterialMoveSiteEntity::getTenantId, tenant)
                .eq(WmsMaterialMoveSiteEntity::getFactoryCode, factory);
        String warehouse = StrUtil.trim(query.getWarehouseCode());
        wrapper.eq(StrUtil.isNotBlank(warehouse), WmsMaterialMoveSiteEntity::getWarehouseCode, warehouse)
                .like(StrUtil.isNotBlank(query.getMatCode()), WmsMaterialMoveSiteEntity::getMatCode,
                        StrUtil.trim(query.getMatCode()))
                .like(StrUtil.isNotBlank(query.getMatName()), WmsMaterialMoveSiteEntity::getMatName,
                        StrUtil.trim(query.getMatName()))
                .like(StrUtil.isNotBlank(query.getNewSiteCode()), WmsMaterialMoveSiteEntity::getNewSiteCode,
                        StrUtil.trim(query.getNewSiteCode()))
                .like(StrUtil.isNotBlank(query.getNewSiteName()), WmsMaterialMoveSiteEntity::getNewSiteName,
                        StrUtil.trim(query.getNewSiteName()))
                .like(StrUtil.isNotBlank(query.getOldSiteCode()), WmsMaterialMoveSiteEntity::getOldSiteCode,
                        StrUtil.trim(query.getOldSiteCode()))
                .like(StrUtil.isNotBlank(query.getOldSiteName()), WmsMaterialMoveSiteEntity::getOldSiteName,
                        StrUtil.trim(query.getOldSiteName()));
        if (ids != null && ids.length > 0) {
            wrapper.in(WmsMaterialMoveSiteEntity::getId, Arrays.asList(ids));
        }
        return wrapper.orderByDesc(WmsMaterialMoveSiteEntity::getCreateTime)
                .orderByDesc(WmsMaterialMoveSiteEntity::getId);
    }
}
