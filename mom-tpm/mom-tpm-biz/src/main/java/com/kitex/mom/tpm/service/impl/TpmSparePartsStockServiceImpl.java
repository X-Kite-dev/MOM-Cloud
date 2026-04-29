package com.kitex.mom.tpm.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.tpm.entity.TpmSparePartsStockEntity;
import com.kitex.mom.tpm.mapper.TpmSparePartsStockMapper;
import com.kitex.mom.tpm.service.TpmSparePartsStockService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 备件库存表
 *
 * @author kitex
 * @date 2025-02-11 16:51:06
 */
@Service
public class TpmSparePartsStockServiceImpl extends ServiceImpl<TpmSparePartsStockMapper, TpmSparePartsStockEntity> implements TpmSparePartsStockService {
    @Override
    public Page<TpmSparePartsStockEntity> pageList(Page toMpPage, TpmSparePartsStockEntity tpmSparePartsStock) {
        return this.baseMapper.pageList(toMpPage, tpmSparePartsStock);
    }

    @Override
    public List<TpmSparePartsStockEntity> getTpmSparePartsStockList(Long[] ids) {
        return this.baseMapper.list(ids);
    }

    @Override
    public TpmSparePartsStockEntity getStockById(Long id) {
        return this.baseMapper.getStockById(id);
    }
}