package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.entity.MesWarehouseMaterialInBillEntity;
import com.kitex.mom.mes.vo.MesWarehouseMaterialInBillExcelVO;

import java.util.List;

public interface MesWarehouseMaterialInBillService extends IService<MesWarehouseMaterialInBillEntity> {

    /**
     * 新增生产领料-通过出库单
     */
    Boolean saveByWmsOutBill(List<Long> list);

    Boolean saveRecord(MesWarehouseMaterialInBillEntity mesWarehouseMaterialInBill);

    /**
     * 设置库位并入库
     *
     * @param entity
     * @return
     */
    Boolean inStock(MesWarehouseMaterialInBillEntity entity);

    void doInStock(MesWarehouseMaterialInBillEntity bill);

    List<MesWarehouseMaterialInBillExcelVO> listExport(LambdaQueryWrapper<MesWarehouseMaterialInBillEntity> wrapper);
}