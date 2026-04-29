package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.tpm.entity.TpmSparePartsLocationEntity;
import com.kitex.mom.tpm.entity.TpmSparePartsStockEntity;
import com.kitex.mom.tpm.entity.TpmSparePartsStoreroomEntity;
import com.kitex.mom.tpm.mapper.TpmSparePartsLocationMapper;
import com.kitex.mom.tpm.mapper.TpmSparePartsStockMapper;
import com.kitex.mom.tpm.mapper.TpmSparePartsStoreroomMapper;
import com.kitex.mom.tpm.service.TpmSparePartsLocationService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 备件库位表
 *
 * @author kitex
 * @date 2025-02-11 16:46:35
 */
@Service
@AllArgsConstructor
public class TpmSparePartsLocationServiceImpl extends ServiceImpl<TpmSparePartsLocationMapper, TpmSparePartsLocationEntity> implements TpmSparePartsLocationService {

    private final TpmSparePartsStoreroomMapper tpmSparePartsStoreroomMapper;
    private final TpmSparePartsStockMapper tpmSparePartsStockMapper;

    @Override
    public Page<TpmSparePartsLocationEntity> pageList(Page toMpPage, TpmSparePartsLocationEntity tpmSparePartsLocation) {
        return this.baseMapper.pageList(toMpPage, tpmSparePartsLocation);
    }

    @Override
    public TpmSparePartsLocationEntity getLocationById(Long id) {
        TpmSparePartsLocationEntity byId = this.getById(id);
        //查询库房名称
        TpmSparePartsStoreroomEntity storeroomEntity = tpmSparePartsStoreroomMapper.selectOne(new LambdaQueryWrapper<TpmSparePartsStoreroomEntity>()
                .eq(TpmSparePartsStoreroomEntity::getCode, byId.getStoreroomCode()));
        byId.setStoreroomName(ObjectUtil.isNotEmpty(storeroomEntity) ? storeroomEntity.getName() : null);
        return byId;
    }

    @Override
    public Boolean removeLocationById(ArrayList<Long> toList) {
        if (CollUtil.isEmpty(toList)) {
            throw new RuntimeException("删除项为空！");
        }
        List<TpmSparePartsLocationEntity> tpmSparePartsLocationList = this.listByIds(toList);
        List<String> codeList = tpmSparePartsLocationList.stream().map(TpmSparePartsLocationEntity::getCode).toList();
        //查询库位是否存在备件
        Long aLong = tpmSparePartsStockMapper.selectCount(new LambdaQueryWrapper<TpmSparePartsStockEntity>()
                .in(TpmSparePartsStockEntity::getLocationCode, codeList)
                .gt(TpmSparePartsStockEntity::getInventoryNum, BigDecimal.ZERO));
        if (aLong.compareTo(0L) > 0) {
            throw new RuntimeException("库位下存在备件，请先处理备件！");
        }
        return this.removeBatchByIds(toList);
    }
}