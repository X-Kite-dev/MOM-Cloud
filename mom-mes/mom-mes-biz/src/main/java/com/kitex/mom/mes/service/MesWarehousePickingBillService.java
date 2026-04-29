package com.kitex.mom.mes.service;

import com.github.yulichang.extension.mapping.base.MPJDeepService;
import com.kitex.mom.mes.dto.MesPickingReceiveMatDTO;
import com.kitex.mom.mes.dto.MesPickingWmsOutBillBackDTO;
import com.kitex.mom.mes.entity.MesWarehousePickingBillEntity;

public interface MesWarehousePickingBillService extends MPJDeepService<MesWarehousePickingBillEntity> {
    Boolean saveDeep(MesWarehousePickingBillEntity entity);

    Boolean updateDeep(MesWarehousePickingBillEntity entity);

    Boolean removeDeep(Long[] ids);

    Boolean removeChild(Long[] ids);

    Boolean commitBill(Long id);

    Boolean withdraw(Long id);

    Boolean wmsOutBillBack(MesPickingWmsOutBillBackDTO dto);

    Boolean receiveBill(MesPickingReceiveMatDTO dto);
}