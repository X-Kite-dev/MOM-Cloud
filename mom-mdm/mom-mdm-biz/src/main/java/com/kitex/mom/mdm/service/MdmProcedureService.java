package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmProcedureEntity;
import com.kitex.mom.mdm.vo.excel.MdmWorkingProcedureExcelVO;
import org.springframework.validation.BindingResult;

import java.util.List;

public interface MdmProcedureService extends IService<MdmProcedureEntity> {

    /**
     * 导入excel 表格
     *
     * @param excelVOList   工序列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    R importWorkingProcedure(List<MdmWorkingProcedureExcelVO> excelVOList, BindingResult bindingResult);

    /**
     * 导出excel 表格
     *
     * @param mdmWorkingProcedure 查询条件
     * @param ids                 导出指定ID
     * @return excel 文件流
     */
    List<MdmWorkingProcedureExcelVO> export(MdmProcedureEntity mdmWorkingProcedure, Long[] ids);

}