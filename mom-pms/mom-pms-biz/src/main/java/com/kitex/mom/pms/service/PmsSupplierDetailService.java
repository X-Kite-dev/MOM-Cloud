package com.kitex.mom.pms.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mdm.entity.MdmSupplierEntity;
import com.kitex.mom.pms.dto.PmsSupplierDTO;
import com.kitex.mom.pms.entity.PmsSupplierDetailEntity;
import com.kitex.mom.pms.vo.PmsSupplierVO;

public interface PmsSupplierDetailService extends IService<PmsSupplierDetailEntity> {

    /**
     * 分页查询
     *
     * @param page        分页对象
     * @param mdmSupplier 供应商表
     * @return
     */
    Page<MdmSupplierEntity> getSupplierPage(Page page, MdmSupplierEntity mdmSupplier);

    /**
     * 通过id查询供应商表
     *
     * @param id id
     * @return R
     */
    PmsSupplierVO getSupplierById(Long id);

    /**
     * 新增供应商
     *
     * @param pmsSupplierDTO 供应商DTO
     * @return R
     */
    Boolean saveSupplier(PmsSupplierDTO pmsSupplierDTO);

    /**
     * 修改供应商表
     *
     * @param pmsSupplierDTO 供应商表
     * @return R
     */
    Boolean updateSupplierById(PmsSupplierDTO pmsSupplierDTO);

    /**
     * 通过id删除供应商详情表
     *
     * @param ids id列表
     * @return R
     */
    Boolean removeBatchSupplier(Long[] ids);
}