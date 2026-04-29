package com.kitex.mom.pms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.pms.dto.PmsPriceDTO;
import com.kitex.mom.pms.entity.PmsPriceEntity;

import java.util.List;

public interface PmsPriceService extends IService<PmsPriceEntity> {

    /**
     * 新增价格库
     *
     * @param pmsPriceDTO 价格库DTO
     * @return R
     */
    Boolean savePrice(PmsPriceDTO pmsPriceDTO);

    /**
     * 通过供应商编码和物料编码查询价格库
     *
     * @param supplierCode 供应商编码
     * @param matCodes     物料编码列表
     * @return R
     */
    List<PmsPriceEntity> getPriceByCode(String supplierCode, List<String> matCodes);
}