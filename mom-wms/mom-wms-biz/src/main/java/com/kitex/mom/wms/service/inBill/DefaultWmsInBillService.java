package com.kitex.mom.wms.service.inBill;

import com.kitex.mom.wms.entity.WmsInBillEntity;
import org.springframework.stereotype.Component;

@Component
public class DefaultWmsInBillService extends AbstractWmsInBillHandler {

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
        // 无操作
    }
}
