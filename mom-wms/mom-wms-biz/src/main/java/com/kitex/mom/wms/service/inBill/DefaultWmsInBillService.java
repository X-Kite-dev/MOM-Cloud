package com.kitex.mom.wms.service.inBill;

import com.kitex.mom.wms.entity.WmsInBillEntity;
import com.kitex.mom.wms.service.impl.WmsInventoryAdjustmentContext;
import org.springframework.stereotype.Component;

@Component
public class DefaultWmsInBillService extends AbstractWmsInBillHandler {

    @Override
    public void checkData(WmsInBillEntity bill) {
        if (bill != null) {
            WmsInventoryAdjustmentContext.check(bill.getBillType(), bill.getDocumentId(), bill.getDocumentTable());
        }
        super.checkData(bill);
    }

    /**
     * 支持的单据类型
     *
     * @param billType
     */
    @Override
    public Boolean support(String billType) {
        return Boolean.FALSE;
    }

    @Override
    public void handleDocumentBizWhenAdd(WmsInBillEntity bill) {
        WmsInventoryAdjustmentContext.check(bill.getBillType(), bill.getDocumentId(), bill.getDocumentTable());
    }
}
