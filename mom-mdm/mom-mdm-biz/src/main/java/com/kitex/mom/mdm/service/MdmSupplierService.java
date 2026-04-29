package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmSupplierEntity;
import com.kitex.mom.mdm.vo.excel.MdmSupplierExcelVO;
import org.springframework.validation.BindingResult;

import java.util.List;

public interface MdmSupplierService extends IService<MdmSupplierEntity> {

    /**
     * 导出供应商
     *
     * @param mdmSupplier
     * @param ids
     * @return
     */
    List<MdmSupplierExcelVO> export(MdmSupplierEntity mdmSupplier, Long[] ids);

    /**
     * 导入excel 表格
     *
     * @param excelVOList   供应商列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    R importSupplier(List<MdmSupplierExcelVO> excelVOList, BindingResult bindingResult);

    /**
     * 审核通过/驳回
     *
     * @param mdmSupplierEntity 供应商表
     * @return R
     */
    Boolean check(MdmSupplierEntity mdmSupplierEntity);
}