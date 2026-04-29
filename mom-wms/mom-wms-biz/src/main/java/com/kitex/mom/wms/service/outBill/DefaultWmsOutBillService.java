package com.kitex.mom.wms.service.outBill;

import com.kitex.mom.wms.entity.WmsOutBillEntity;
import org.springframework.stereotype.Component;

@Component
public class DefaultWmsOutBillService extends AbstractWmsOutBillHandler {

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
    }

}
