package com.kitex.mom.wms.service.inOutFlow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.wms.entity.WmsInoutFlowEntity;
import com.kitex.mom.wms.vo.WmsInoutFlowExcelVO;

import java.util.List;

/**
 * 出入库流水
 *
 * @param <T>
 */
public interface WmsInoutFlowHandler<T extends WmsInoutFlowEntity> {

    Boolean support(String billType);

    Page<T> page(Page page, LambdaQueryWrapper<T> wrapper);

    List<T> list(LambdaQueryWrapper<T> wrapper);

    /**
     * 获取导出列表
     */
    List<WmsInoutFlowExcelVO> listExcel(LambdaQueryWrapper<T> wrapper);
}