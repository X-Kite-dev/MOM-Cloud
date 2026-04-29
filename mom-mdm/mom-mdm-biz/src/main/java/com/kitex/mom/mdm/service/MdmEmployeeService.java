package com.kitex.mom.mdm.service;

import com.github.yulichang.extension.mapping.base.MPJDeepService;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmEmployeeEntity;
import com.kitex.mom.mdm.vo.excel.MdmEmployeeExcelVO;
import org.springframework.validation.BindingResult;

import java.util.List;

public interface MdmEmployeeService extends MPJDeepService<MdmEmployeeEntity> {
    Boolean saveDeep(MdmEmployeeEntity mdmEmployee);

    Boolean updateDeep(MdmEmployeeEntity mdmEmployee);

    Boolean removeDeep(Long[] ids);

    /**
     * 导入excel 表
     *
     * @param excelVOList   对象实体列表
     * @param bindingResult 错误信息列表
     * @return ok fail
     */
    R importEmployee(List<MdmEmployeeExcelVO> excelVOList, BindingResult bindingResult);

    /**
     * 导出excel 表格
     *
     * @param mdmEmployee 查询条件
     * @param ids         导出指定ID
     * @return excel 文件流
     */
    List<MdmEmployeeExcelVO> exportEmployee(MdmEmployeeEntity mdmEmployee, Long[] ids);

    /**
     * 同步员工到用户表
     *
     * @param ids 员工ids
     * @return R
     */
    Boolean syncEmployeeToUser(Long[] ids);
}