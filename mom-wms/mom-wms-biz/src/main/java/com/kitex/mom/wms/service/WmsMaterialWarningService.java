package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.wms.dto.WmsMaterialWarningQueryDTO;
import com.kitex.mom.wms.entity.WmsMaterialWarningEntity;
import com.kitex.mom.wms.vo.WmsMaterialWarningExcelVO;

import java.util.List;

public interface WmsMaterialWarningService extends IService<WmsMaterialWarningEntity> {

    /**
     * 每次创建新查询，必须带当前租户工厂；业务筛选不能改写隔离条件。
     */
    LambdaQueryWrapper<WmsMaterialWarningEntity> query(WmsMaterialWarningQueryDTO query);

    WmsMaterialWarningEntity getScopedById(Long id);

    /**
     * 导出excel 表格
     *
     * @param wmsMaterialWarning 查询条件
     * @param ids                导出指定ID
     * @return excel 文件流
     */
    List<WmsMaterialWarningExcelVO> export(WmsMaterialWarningQueryDTO wmsMaterialWarning, Long[] ids);
}
