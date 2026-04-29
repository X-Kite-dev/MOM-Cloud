package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.util.R;
import com.kitex.mom.common.barcode.dto.req.PrintReq;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.vo.excel.MdmMaterialExcelVO;
import org.springframework.validation.BindingResult;

import java.util.List;

public interface MdmMaterialService extends IService<MdmMaterialEntity> {

    /**
     * 导入excel 表格
     *
     * @param excelVOList   物料列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    R importMaterial(List<MdmMaterialExcelVO> excelVOList, BindingResult bindingResult);

    /**
     * 导出excel 表格
     *
     * @param mdmMaterial 查询条件
     * @param ids         导出指定ID
     * @return excel 文件流
     */
    List<MdmMaterialExcelVO> export(MdmMaterialEntity mdmMaterial, Long[] ids);

    /**
     * 物料打印
     *
     * @param printReq 打印Req
     * @return R 对象
     */
    String print(PrintReq printReq);
}