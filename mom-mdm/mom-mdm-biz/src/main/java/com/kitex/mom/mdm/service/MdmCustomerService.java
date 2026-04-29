package com.kitex.mom.mdm.service;

import com.github.yulichang.extension.mapping.base.MPJDeepService;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmCustomerEntity;
import com.kitex.mom.mdm.vo.excel.MdmCustomerExcelVO;
import org.springframework.validation.BindingResult;

import java.util.List;

public interface MdmCustomerService extends MPJDeepService<MdmCustomerEntity> {

    /**
     * 新增客户表
     *
     * @param mdmCustomer 客户表
     * @return R
     */
    Boolean saveDeep(MdmCustomerEntity mdmCustomer);

    /**
     * 修改客户表
     *
     * @param mdmCustomer 客户表
     * @return R
     */
    Boolean updateDeep(MdmCustomerEntity mdmCustomer);

    /**
     * 通过id删除客户表
     *
     * @param ids id列表
     * @return R
     */
    Boolean removeDeep(Long[] ids);

    /**
     * 导出
     *
     * @param mdmCustomer
     * @param ids
     * @return
     */
    List<MdmCustomerExcelVO> export(MdmCustomerEntity mdmCustomer, Long[] ids);

    /**
     * 导入
     *
     * @param mdmCustomerList
     * @param bindingResult
     * @return
     */
    R importExcel(List<MdmCustomerExcelVO> mdmCustomerList, BindingResult bindingResult);

}