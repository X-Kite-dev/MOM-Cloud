package com.kitex.mom.wms.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.dto.WmsMaterialWarningQueryDTO;
import com.kitex.mom.wms.entity.WmsMaterialWarningEntity;
import com.kitex.mom.wms.mapper.WmsMaterialWarningMapper;
import com.kitex.mom.wms.service.WmsMaterialWarningService;
import com.kitex.mom.wms.vo.WmsMaterialWarningExcelVO;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WmsMaterialWarningServiceImpl extends ServiceImpl<WmsMaterialWarningMapper, WmsMaterialWarningEntity>
        implements WmsMaterialWarningService {
    @Override
    public LambdaQueryWrapper<WmsMaterialWarningEntity> query(WmsMaterialWarningQueryDTO q) {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        if (tenant == null || tenant <= 0 || StrUtil.isBlank(factory) || TenantContextHolder.getTenantSkip()
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())) {
            throw new CheckedException("查询物料预警需要当前租户工厂，禁止跳过隔离");
        }
        if (q == null) {
            q = new WmsMaterialWarningQueryDTO();
        }
        var wrapper = new LambdaQueryWrapper<WmsMaterialWarningEntity>()
                .eq(WmsMaterialWarningEntity::getTenantId, tenant).eq(WmsMaterialWarningEntity::getFactoryCode, factory)
                .like(StrUtil.isNotBlank(q.getBillNo()), WmsMaterialWarningEntity::getBillNo, q.getBillNo())
                .like(StrUtil.isNotBlank(q.getMatCode()), WmsMaterialWarningEntity::getMatCode, q.getMatCode())
                .like(StrUtil.isNotBlank(q.getMatName()), WmsMaterialWarningEntity::getMatName, q.getMatName())
                .like(StrUtil.isNotBlank(q.getMatBatch()), WmsMaterialWarningEntity::getMatBatch, q.getMatBatch())
                .like(StrUtil.isNotBlank(q.getWarehouseCode()), WmsMaterialWarningEntity::getWarehouseCode,
                        q.getWarehouseCode())
                .like(StrUtil.isNotBlank(q.getWarehouseName()), WmsMaterialWarningEntity::getWarehouseName,
                        q.getWarehouseName())
                .like(StrUtil.isNotBlank(q.getSiteCode()), WmsMaterialWarningEntity::getSiteCode, q.getSiteCode())
                .like(StrUtil.isNotBlank(q.getSiteName()), WmsMaterialWarningEntity::getSiteName, q.getSiteName())
                .eq(StrUtil.isNotBlank(q.getType()), WmsMaterialWarningEntity::getType, q.getType());
        if (ArrayUtil.isNotEmpty(q.getCreateTime())) {
            if (q.getCreateTime().length != 2 || q.getCreateTime()[0] == null || q.getCreateTime()[1] == null
                    || q.getCreateTime()[0].isAfter(q.getCreateTime()[1])) {
                throw new CheckedException("预警查询时间范围无效");
            }
            wrapper.ge(WmsMaterialWarningEntity::getCreateTime, q.getCreateTime()[0].atStartOfDay())
                    .lt(WmsMaterialWarningEntity::getCreateTime, q.getCreateTime()[1].plusDays(1).atStartOfDay());
        }
        return wrapper.orderByDesc(WmsMaterialWarningEntity::getCreateTime)
                .orderByDesc(WmsMaterialWarningEntity::getId);
    }

    @Override
    public WmsMaterialWarningEntity getScopedById(Long id) {
        if (id == null) {
            throw new CheckedException("预警ID不能为空");
        }
        return getOne(query(null).eq(WmsMaterialWarningEntity::getId, id));
    }

    @Override
    public List<WmsMaterialWarningExcelVO> export(WmsMaterialWarningQueryDTO q, Long[] ids) {
        return list(query(q).in(ArrayUtil.isNotEmpty(ids), WmsMaterialWarningEntity::getId, ids)).stream().map(row -> {
            WmsMaterialWarningExcelVO result = new WmsMaterialWarningExcelVO();
            BeanUtil.copyProperties(row, result);
            result.setWarningDeadline(row.getWarningDeadline());
            result.setExcessDays(row.getExcessDays());
            return result;
        }).toList();
    }
}
