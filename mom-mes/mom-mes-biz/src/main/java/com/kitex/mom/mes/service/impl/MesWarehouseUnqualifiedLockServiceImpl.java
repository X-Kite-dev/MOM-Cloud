package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesWarehouseUnqualifiedLockAddLockDTO;
import com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity;
import com.kitex.mom.mes.entity.MesWarehouseLockDetailEntity;
import com.kitex.mom.mes.entity.MesWarehouseUnqualifiedLockEntity;
import com.kitex.mom.mes.enums.YesNoEnum;
import com.kitex.mom.mes.mapper.MesWarehouseLiveStockMapper;
import com.kitex.mom.mes.mapper.MesWarehouseLockDetailMapper;
import com.kitex.mom.mes.mapper.MesWarehouseUnqualifiedLockMapper;
import com.kitex.mom.mes.service.MesWarehouseUnqualifiedLockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 不合格锁定记录
 *
 * @author kitex
 * @date 2025-02-21 10:44:33
 */
@Service
@RequiredArgsConstructor
public class MesWarehouseUnqualifiedLockServiceImpl extends ServiceImpl<MesWarehouseUnqualifiedLockMapper, MesWarehouseUnqualifiedLockEntity> implements MesWarehouseUnqualifiedLockService {

    private final MesWarehouseLockDetailMapper mesWarehouseLockDetailMapper;
    private final MesWarehouseLiveStockMapper mesWarehouseLiveStockMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeep(MesWarehouseUnqualifiedLockEntity mesWarehouseUnqualifiedLock) {
        baseMapper.insert(mesWarehouseUnqualifiedLock);
        for (MesWarehouseLockDetailEntity mesWarehouseLockDetail : mesWarehouseUnqualifiedLock.getMesWarehouseLockDetailList()) {
            mesWarehouseLockDetail.setLockId(mesWarehouseUnqualifiedLock.getId());
            mesWarehouseLockDetailMapper.insert(mesWarehouseLockDetail);
        }

        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDeep(MesWarehouseUnqualifiedLockEntity mesWarehouseUnqualifiedLock) {
        baseMapper.updateById(mesWarehouseUnqualifiedLock);
        for (MesWarehouseLockDetailEntity mesWarehouseLockDetail : mesWarehouseUnqualifiedLock.getMesWarehouseLockDetailList()) {
            if (Objects.isNull(mesWarehouseLockDetail.getId())) {
                mesWarehouseLockDetail.setLockId(mesWarehouseUnqualifiedLock.getId());
                mesWarehouseLockDetailMapper.insert(mesWarehouseLockDetail);
            } else {
                mesWarehouseLockDetailMapper.updateById(mesWarehouseLockDetail);
            }
        }
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeDeep(Long[] ids) {
        baseMapper.deleteBatchIds(CollUtil.toList(ids));
        mesWarehouseLockDetailMapper.delete(Wrappers.<MesWarehouseLockDetailEntity>lambdaQuery().in(MesWarehouseLockDetailEntity::getLockId, ids));
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeChild(Long[] ids) {
        mesWarehouseLockDetailMapper.deleteBatchIds(CollUtil.toList(ids));
        return Boolean.TRUE;
    }

    /**
     * 添加锁定
     *
     * @param dto 锁定条件
     */
    @Override
    @Transactional
    public Boolean addLock(MesWarehouseUnqualifiedLockAddLockDTO dto) {
        String matCode = dto.getMatCode();
        if (StrUtil.isBlank(matCode)) {
            throw new CheckedException("物料编码为空！");
        }
        // 获取库存信息
        List<MesWarehouseLiveStockEntity> stockList = mesWarehouseLiveStockMapper.selectList(new LambdaQueryWrapper<MesWarehouseLiveStockEntity>()
                .eq(MesWarehouseLiveStockEntity::getMatCode, matCode)
                .eq(StrUtil.isNotBlank(dto.getMatBatch()), MesWarehouseLiveStockEntity::getMatBatch, dto.getMatBatch())
                .eq(StrUtil.isNotBlank(dto.getSiteCode()), MesWarehouseLiveStockEntity::getSiteCode, dto.getSiteCode())
                .eq(StrUtil.isNotBlank(dto.getContainerCode()), MesWarehouseLiveStockEntity::getContainerCode, dto.getContainerCode())
                .eq(StrUtil.isNotBlank(dto.getSupplierCode()), MesWarehouseLiveStockEntity::getSupplierCode, dto.getSupplierCode())
                .ne(MesWarehouseLiveStockEntity::getStatus, YesNoEnum.YES.getCode())
        );
        // 修改库存状态
        List<Long> ids = stockList.stream().map(BaseEntity::getId).toList();
        if (CollUtil.isEmpty(ids)) {
            throw new CheckedException("未查找到对应物料或物料已被锁定！");
        }
        mesWarehouseLiveStockMapper.update(new LambdaUpdateWrapper<MesWarehouseLiveStockEntity>()
                .set(MesWarehouseLiveStockEntity::getStatus, YesNoEnum.YES.getCode())
                .in(BaseEntity::getId, ids)
        );
        // 保存锁定记录
        MesWarehouseUnqualifiedLockEntity entity = new MesWarehouseUnqualifiedLockEntity();
        BeanUtil.copyProperties(dto, entity);
        entity.setLockTime(LocalDateTime.now());
        MomUser user = SecurityUtils.getUser();
        entity.setLockPersonCode(user.getUsername());
        entity.setLockPersonName(user.getName());
        List<MesWarehouseLockDetailEntity> detailEntities = new ArrayList<>();
        for (MesWarehouseLiveStockEntity stock : stockList) {
            MesWarehouseLockDetailEntity detail = new MesWarehouseLockDetailEntity();
            BeanUtil.copyProperties(stock, detail, "id");
            detail.setStockId(stock.getId());
            detail.setLockTime(entity.getLockTime());
            detail.setLockPersonCode(entity.getLockPersonCode());
            detail.setLockPersonName(entity.getLockPersonName());
            detailEntities.add(detail);
        }
        entity.setMesWarehouseLockDetailList(detailEntities);
        saveDeep(entity);
        return Boolean.TRUE;
    }

    /**
     * 解锁
     *
     * @param id id
     */
    @Override
    @Transactional
    public Boolean unlock(Long id) {
        // 查找物料明细
        List<MesWarehouseLockDetailEntity> detailList = mesWarehouseLockDetailMapper.selectList(new LambdaQueryWrapper<MesWarehouseLockDetailEntity>()
                .eq(MesWarehouseLockDetailEntity::getLockId, id)
        );
        // 修改库存状态
        List<Long> ids = detailList.stream().map(MesWarehouseLockDetailEntity::getStockId).toList();
        mesWarehouseLiveStockMapper.update(new LambdaUpdateWrapper<MesWarehouseLiveStockEntity>()
                .set(MesWarehouseLiveStockEntity::getStatus, YesNoEnum.NO.getCode())
                .in(BaseEntity::getId, ids)
        );
        // 修改解锁人
        MomUser user = SecurityUtils.getUser();
        update(new LambdaUpdateWrapper<MesWarehouseUnqualifiedLockEntity>()
                .set(MesWarehouseUnqualifiedLockEntity::getUnlockTime, LocalDateTime.now())
                .set(MesWarehouseUnqualifiedLockEntity::getUnlockPersonCode, user.getUsername())
                .set(MesWarehouseUnqualifiedLockEntity::getUnlockPersonName, user.getName())
                .eq(BaseEntity::getId, id)
        );
        mesWarehouseLockDetailMapper.update(new LambdaUpdateWrapper<MesWarehouseLockDetailEntity>()
                .set(MesWarehouseLockDetailEntity::getUnlockTime, LocalDateTime.now())
                .set(MesWarehouseLockDetailEntity::getUnlockPersonCode, user.getUsername())
                .set(MesWarehouseLockDetailEntity::getUnlockPersonName, user.getName())
                .eq(MesWarehouseLockDetailEntity::getLockId, id)
        );
        return Boolean.TRUE;
    }
}
