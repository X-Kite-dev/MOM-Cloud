package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.entity.WmsPickingAreaEntity;
import com.kitex.mom.wms.vo.WmsPickingAreaExcelVO;

import java.util.List;

public interface WmsPickingAreaService extends IService<WmsPickingAreaEntity> {

    /**
     * 将削减的库存物料放到拣货区
     *
     * @param stockOuted 实际扣减的库存行
     * @param outBill 出库单
     * @param outBillDetailId 本次出库明细ID
     * @return 是否全部接收
     */
    Boolean insertByStockList(List<WmsLiveStockEntity> stockOuted, WmsOutBillEntity outBill, Long outBillDetailId);

    /**
     * 删除拣货区物料 记录出库流水
     */
    Boolean deleteByOutBill(WmsOutBillEntity outBill);

    /**
     * 导出
     *
     * @param wrapper 查询条件
     * @return
     */
    List<WmsPickingAreaExcelVO> exportExcel(LambdaQueryWrapper<WmsPickingAreaEntity> wrapper);
}
