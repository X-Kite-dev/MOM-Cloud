package com.kitex.mom.mes.service;

import com.github.yulichang.extension.mapping.base.MPJDeepService;
import com.kitex.mom.mes.dto.MesWarehouseUnqualifiedLockAddLockDTO;
import com.kitex.mom.mes.entity.MesWarehouseUnqualifiedLockEntity;

public interface MesWarehouseUnqualifiedLockService extends MPJDeepService<MesWarehouseUnqualifiedLockEntity> {
    Boolean saveDeep(MesWarehouseUnqualifiedLockEntity mesWarehouseUnqualifiedLock);

    Boolean updateDeep(MesWarehouseUnqualifiedLockEntity mesWarehouseUnqualifiedLock);

    Boolean removeDeep(Long[] ids);

    Boolean removeChild(Long[] ids);

    /**
     * 添加锁定
     *
     * @param dto 锁定条件
     */
    Boolean addLock(MesWarehouseUnqualifiedLockAddLockDTO dto);

    /**
     * 解锁
     *
     * @param id id
     */
    Boolean unlock(Long id);
}