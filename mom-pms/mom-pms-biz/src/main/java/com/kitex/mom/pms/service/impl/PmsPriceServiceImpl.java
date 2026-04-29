package com.kitex.mom.pms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.pms.dto.PmsPriceDTO;
import com.kitex.mom.pms.dto.PmsPriceMatDTO;
import com.kitex.mom.pms.entity.PmsPriceEntity;
import com.kitex.mom.pms.mapper.PmsPriceMapper;
import com.kitex.mom.pms.service.PmsPriceService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 价格库
 *
 * @author kitex
 * @date 2025-03-24 17:39:49
 */
@Service
public class PmsPriceServiceImpl extends ServiceImpl<PmsPriceMapper, PmsPriceEntity> implements PmsPriceService {

    /**
     * 新增价格库
     *
     * @param pmsPriceDTO 价格库DTO
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean savePrice(PmsPriceDTO pmsPriceDTO) {
        List<PmsPriceEntity> saveList = new ArrayList<>();
        List<String> matCodes = pmsPriceDTO.getMatList().stream().map(PmsPriceMatDTO::getMatCode).toList();
        if (!matCodes.isEmpty()) {
            this.remove(new LambdaQueryWrapper<PmsPriceEntity>().eq(PmsPriceEntity::getSupplierCode, pmsPriceDTO.getSupplierCode())
                    .in(PmsPriceEntity::getMatCode, matCodes));
        }

        for (PmsPriceMatDTO pmsPriceMatDTO : pmsPriceDTO.getMatList()) {
            PmsPriceEntity pmsPriceEntity = new PmsPriceEntity();
            BeanUtils.copyProperties(pmsPriceMatDTO, pmsPriceEntity);
            pmsPriceEntity.setSupplierCode(pmsPriceDTO.getSupplierCode());
            pmsPriceEntity.setSupplierName(pmsPriceDTO.getSupplierName());
            pmsPriceEntity.setFactoryCode(pmsPriceDTO.getFactoryCode());
            saveList.add(pmsPriceEntity);
        }
        return this.saveBatch(saveList);
    }

    /**
     * 通过供应商编码和物料编码查询价格库
     *
     * @param supplierCode 供应商编码
     * @param matCodes     物料编码列表
     * @return R
     */
    @Override
    public List<PmsPriceEntity> getPriceByCode(String supplierCode, List<String> matCodes) {
        return this.list(new LambdaQueryWrapper<PmsPriceEntity>().eq(PmsPriceEntity::getSupplierCode, supplierCode)
                .in(PmsPriceEntity::getMatCode, matCodes));
    }
}