package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmCustomerPartEntity;
import com.kitex.mom.mdm.vo.excel.MdmCustomerPartExcelVO;
import org.springframework.validation.BindingResult;

import java.util.List;

public interface MdmCustomerPartService extends IService<MdmCustomerPartEntity> {

    /**
     * 导出excel 表格
     *
     * @param mdmCustomerPart 查询条件
     * @param ids             导出指定ID
     * @return excel 文件流
     */
    List<MdmCustomerPartExcelVO> export(MdmCustomerPartEntity mdmCustomerPart, Long[] ids);

    /**
     * 导入excel 表格
     *
     * @param excelVOList   客户零件号列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    R importCustomerPart(List<MdmCustomerPartExcelVO> excelVOList, BindingResult bindingResult);

}