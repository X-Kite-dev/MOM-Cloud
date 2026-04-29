package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.wms.entity.WmsMaterialWarningEntity;
import com.kitex.mom.wms.vo.WmsMaterialWarningExcelVO;

import java.util.List;

public interface WmsMaterialWarningService extends IService<WmsMaterialWarningEntity> {

    /**
     * 导出excel 表格
     *
     * @param wmsMaterialWarning 查询条件
     * @param ids                导出指定ID
     * @return excel 文件流
     */
    List<WmsMaterialWarningExcelVO> export(WmsMaterialWarningEntity wmsMaterialWarning, Long[] ids);
}