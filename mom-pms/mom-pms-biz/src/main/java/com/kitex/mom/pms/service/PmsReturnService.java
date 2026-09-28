package com.kitex.mom.pms.service;

import com.github.yulichang.extension.mapping.base.MPJDeepService;
import com.kitex.mom.pms.dto.PmsSupplierReturnCommand;
import com.kitex.mom.pms.dto.PmsSupplierReturnEvent;
import com.kitex.mom.pms.entity.PmsReturnEntity;
import com.kitex.mom.wms.dto.WmsSupplierReturnCommand;

import java.util.List;

public interface PmsReturnService extends MPJDeepService<PmsReturnEntity> {
    Boolean saveDeep(PmsReturnEntity pmsReturn);

    Boolean updateDeep(PmsReturnEntity pmsReturn);

    Boolean removeDeep(Long[] ids);

    List<WmsSupplierReturnCommand.StockCandidate> eligibleStocks(WmsSupplierReturnCommand.StockQuery query);

    PmsReturnEntity submit(PmsSupplierReturnCommand.Submit command);

    PmsReturnEntity review(PmsSupplierReturnCommand.Review command);

    PmsReturnEntity cancel(PmsSupplierReturnCommand.Cancel command);

    PmsSupplierReturnEvent.Receipt receiveCompleted(PmsSupplierReturnEvent event);
}
