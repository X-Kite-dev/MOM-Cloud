package com.kitex.mom.pms.service;

import com.kitex.mom.pms.dto.PmsSupplierDeliverDTO;
import com.kitex.mom.pms.vo.PmsSupplierDeliverVO;

import java.util.List;

public interface PmsSupplierDeliverService {

    /**
     * 通过id查询供货信息
     *
     * @param id id
     * @return R
     */
    PmsSupplierDeliverVO getSupplierDeliverById(Long id);

    /**
     * 修改供应商-供货
     *
     * @param pmsSupplierDeliverDTO 供应商-供货表
     * @return R
     */
    Boolean updateSupplierDeliverById(PmsSupplierDeliverDTO pmsSupplierDeliverDTO);

    /**
     * 根据供应商编码查询供货物料编码
     *
     * @param supplierCode 供应商编码
     * @return R
     */
    List<String> getMatCodes(String supplierCode);
}