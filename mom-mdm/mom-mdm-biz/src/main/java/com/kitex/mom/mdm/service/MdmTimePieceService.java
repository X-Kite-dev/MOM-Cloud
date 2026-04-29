package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmTimePieceEntity;
import com.kitex.mom.mdm.vo.excel.MdmTimePieceExcelVO;
import org.springframework.validation.BindingResult;

import java.util.List;

public interface MdmTimePieceService extends IService<MdmTimePieceEntity> {

    /**
     * 新增计时计件表
     *
     * @param mdmTimePiece 计时计件表
     * @return R
     */
    Boolean saveTimePiece(MdmTimePieceEntity mdmTimePiece);

    /**
     * 修改计时计件表
     *
     * @param mdmTimePiece 计时计件表
     * @return R
     */
    Boolean updateTimePiece(MdmTimePieceEntity mdmTimePiece);

    /**
     * 导出excel 表格
     *
     * @param mdmTimePiece 查询条件
     * @param ids          导出指定ID
     * @return excel 文件流
     */
    List<MdmTimePieceExcelVO> exportTimePiece(MdmTimePieceEntity mdmTimePiece, Long[] ids);

    /**
     * 导入计时计件数据
     *
     * @param excelVOList
     * @param bindingResult
     * @return
     */
    R importTimePiece(List<MdmTimePieceExcelVO> excelVOList, BindingResult bindingResult);
}