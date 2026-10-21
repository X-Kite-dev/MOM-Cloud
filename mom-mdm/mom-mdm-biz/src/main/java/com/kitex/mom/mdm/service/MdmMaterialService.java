package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.util.R;
import com.kitex.mom.common.barcode.dto.req.PrintReq;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.dto.MdmMaterialMeasureDTO;
import com.kitex.mom.mdm.vo.excel.MdmMaterialExcelVO;
import org.springframework.validation.BindingResult;

import java.util.List;

public interface MdmMaterialService extends IService<MdmMaterialEntity> {

    /** 实名/实际租户工厂内唯一物料计量观察；只读，不替调用方冻结旧历史。 */
    MdmMaterialMeasureDTO productionMeasure(String materialCode);

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
