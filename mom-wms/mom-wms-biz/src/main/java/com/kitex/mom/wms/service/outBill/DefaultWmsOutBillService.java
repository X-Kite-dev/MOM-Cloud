package com.kitex.mom.wms.service.outBill;

import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.service.impl.WmsInventoryAdjustmentContext;
import org.springframework.stereotype.Component;

@Component
public class DefaultWmsOutBillService extends AbstractWmsOutBillHandler {

    @Override
    public void checkData(WmsOutBillEntity bill) {
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

    /**
     * 处理业务表数据
     *
     * @param bill
     */
    @Override
    public void handleDocumentBizWhenAdd(WmsOutBillEntity bill) {
        WmsInventoryAdjustmentContext.check(bill.getBillType(), bill.getDocumentId(), bill.getDocumentTable());
    }

}
