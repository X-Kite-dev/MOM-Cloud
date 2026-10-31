package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.dto.MdmMaterialWarningConfigDTO;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.entity.WmsMaterialWarningEntity;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import com.kitex.mom.wms.service.WmsMaterialWarningService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 一个库存一笔本地事务，预警记录与首次触发标记原子提交，远程消息在调用方提交后处理。
 */
@Service
@RequiredArgsConstructor
public class WmsMaterialWarningRuntimeService {
    private final WmsLiveStockMapper stocks;
    private final WmsMaterialWarningService warnings;

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public List<WmsMaterialWarningEntity> evaluate(Long stockId, MdmMaterialWarningConfigDTO config) {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        require(tenant != null && tenant > 0 && StrUtil.isNotBlank(factory)
                        && !TenantContextHolder.getTenantSkip() && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()),
                "预警执行需要明确租户和工厂，禁止跳过隔离");
        require(stockId != null && config != null && Objects.equals(config.getTenantId(), tenant)
                && Objects.equals(config.getFactoryCode(), factory), "预警配置与执行范围不一致");
        WmsLiveStockEntity stock = stocks.selectOne(new LambdaQueryWrapper<WmsLiveStockEntity>()
                .eq(WmsLiveStockEntity::getId, stockId).eq(WmsLiveStockEntity::getTenantId, tenant)
                .eq(WmsLiveStockEntity::getFactoryCode, factory).last("FOR UPDATE"));
        if (stock == null) {
            return List.of();
        } // 扫描后已正常耗尽/删除，不使用旧快照写库存。
        require(Objects.equals(stock.getTenantId(), tenant) && Objects.equals(stock.getFactoryCode(), factory)
                && Objects.equals(stock.getMatCode(), config.getMaterialCode()), "库存与预警配置范围不一致");
        require(stock.getMatNum() != null && Double.isFinite(stock.getMatNum()), "库存数量无效");
        if (stock.getMatNum() <= 0) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        List<WmsMaterialWarningEntity> created = new ArrayList<>();
        // 先完成两种规则校验，再产生任何副作用；空开关不推定为启用。
        boolean expiration = false, slow = false;
        if (Boolean.TRUE.equals(config.getExpiration()) && !Boolean.TRUE.equals(stock.getExpirationStatus())) {
            require(config.getExpirationDay() != null && config.getExpirationDay() >= 0, "启用有效期预警但天数缺失或为负");
            require(stock.getMatProduceDate() != null, "启用有效期预警但库存生产日期缺失");
            expiration = now.toLocalDate().isAfter(stock.getMatProduceDate().plusDays(config.getExpirationDay()));
        }
        if (Boolean.TRUE.equals(config.getSlowMaterial()) && !Boolean.TRUE.equals(stock.getSlowMaterialStatus())) {
            require(config.getSlowMaterialDay() != null && config.getSlowMaterialDay() >= 0, "启用呆滞预警但天数缺失或为负");
            require(stock.getInTime() != null, "启用呆滞预警但库存入库时间缺失");
            slow = now.isAfter(stock.getInTime().plusDays(config.getSlowMaterialDay()));
        }
        if (expiration) {
            created.add(saveWarning(stock, "0", config.getExpirationDay(), now));
        }
        if (slow) {
            created.add(saveWarning(stock, "1", config.getSlowMaterialDay(), now));
        }
        if (!created.isEmpty()) {
            WmsLiveStockEntity patch = new WmsLiveStockEntity();
            if (expiration) {
                patch.setExpirationStatus(true);
            }
            if (slow) {
                patch.setSlowMaterialStatus(true);
            }
            require(stocks.update(patch, new LambdaQueryWrapper<WmsLiveStockEntity>()
                    .eq(WmsLiveStockEntity::getId, stockId).eq(WmsLiveStockEntity::getTenantId, tenant)
                    .eq(WmsLiveStockEntity::getFactoryCode, factory)) == 1, "预警库存标记保存失败，预警记录已回滚");
        }
        return created;
    }

    private WmsMaterialWarningEntity saveWarning(WmsLiveStockEntity stock, String type, Integer days,
                                                 LocalDateTime now) {
        WmsMaterialWarningEntity warning = new WmsMaterialWarningEntity();
        BeanUtils.copyProperties(stock, warning);
        warning.setId(null);
        warning.setBillNo("MW-" + stock.getId() + "-" + type);
        warning.setType(type);
        warning.setWarningDays(days);
        warning.setCreateTime(now);
        warning.setCreateBy("SYSTEM:material_warning");
        warning.setUpdateTime(null);
        warning.setUpdateBy(null);
        warning.setDelFlag("0");
        require(warnings.save(warning), "预警记录保存失败，库存标记未提交");
        return warning;
    }
}
