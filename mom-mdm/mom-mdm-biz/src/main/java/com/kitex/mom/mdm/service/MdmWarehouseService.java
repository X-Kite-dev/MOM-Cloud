package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.vo.MdmWarehouseModelTreeVO;
import com.kitex.mom.mdm.vo.excel.MdmWarehouseExcelVO;
import com.kitex.mom.mdm.vo.excel.MdmWarehouseModelExcelVO;
import org.springframework.validation.BindingResult;

import java.util.List;

public interface MdmWarehouseService extends IService<MdmWarehouseEntity> {

    /**
     * 获取仓库模树形结构
     */
    List<MdmWarehouseModelTreeVO> getWarehouseModelTree();

    /**
     * 导入excel 表格
     *
     * @param excelVOList   仓库列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    R importWarehouse(List<MdmWarehouseModelExcelVO> excelVOList, BindingResult bindingResult);

    /**
     * 导出
     *
     * @param mdmWarehouse
     * @return
     */
    List<MdmWarehouseExcelVO> export(MdmWarehouseEntity mdmWarehouse);

    /**
     * 导出excel 表格(仓储、库区)
     *
     * @return excel 文件流
     */
    List<MdmWarehouseModelExcelVO> exportAll();
}