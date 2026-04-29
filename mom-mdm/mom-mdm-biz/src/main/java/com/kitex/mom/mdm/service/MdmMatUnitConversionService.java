package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mdm.entity.MdmMatUnitConversionEntity;
import com.kitex.mom.mdm.vo.excel.MdmMatUnitConversionExcelVO;

import java.util.List;

public interface MdmMatUnitConversionService extends IService<MdmMatUnitConversionEntity> {

    /**
     * 导出excel 表格
     *
     * @param mdmMatUnit 查询条件
     * @param ids        导出指定ID
     * @return excel 文件流
     */
    List<MdmMatUnitConversionExcelVO> exportExcel(MdmMatUnitConversionEntity mdmMatUnit, Long[] ids);
}