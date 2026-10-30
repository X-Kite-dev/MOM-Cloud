package com.kitex.mom.mdm.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.mapper.MdmDeviceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 精确组织范围的实物查询。返回原始状态，由工程配置或执行业务分别判断可用性。
 */
@Component
@RequiredArgsConstructor
public class MdmScopedDeviceQuerySupport {

    private final MdmDeviceMapper deviceMapper;

    public List<MdmDeviceEntity> listByCodes(List<String> codes) {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        if (tenant == null || tenant <= 0 || TenantContextHolder.getTenantSkip()
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) || StrUtil.isBlank(factory)) {
            throw new CheckedException("实物设备查询需要明确当前租户和工厂");
        }
        if (codes == null || codes.size() > 2000 || codes.stream().anyMatch(StrUtil::isBlank)) {
            throw new CheckedException("设备编码集合不可为空编码且不能超过2000项");
        }
        if (codes.isEmpty()) return List.of();
        List<MdmDeviceEntity> rows = deviceMapper.selectList(new LambdaQueryWrapper<MdmDeviceEntity>()
                .eq(MdmDeviceEntity::getTenantId, tenant)
                .eq(MdmDeviceEntity::getFactoryCode, factory)
                .in(MdmDeviceEntity::getDeviceCode, new LinkedHashSet<>(codes))
                .orderByAsc(MdmDeviceEntity::getDeviceCode));
        Set<String> found = new HashSet<>();
        for (MdmDeviceEntity row : rows) {
            if (!found.add(row.getDeviceCode())) {
                throw new CheckedException("当前租户/工厂存在重复实物编码，请先修复主数据");
            }
        }
        return rows;
    }
}
