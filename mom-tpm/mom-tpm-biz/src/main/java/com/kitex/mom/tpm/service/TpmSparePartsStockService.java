package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.tpm.entity.TpmSparePartsStockEntity;

import java.util.List;

public interface TpmSparePartsStockService extends IService<TpmSparePartsStockEntity> {

    Page<TpmSparePartsStockEntity> pageList(Page toMpPage, TpmSparePartsStockEntity tpmSparePartsStock);

    List<TpmSparePartsStockEntity> getTpmSparePartsStockList(Long[] ids);

    TpmSparePartsStockEntity getStockById(Long id);
}