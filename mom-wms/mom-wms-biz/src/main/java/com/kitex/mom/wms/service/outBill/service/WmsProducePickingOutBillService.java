package com.kitex.mom.wms.service.outBill.service;

import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.enums.OutBillTypeEnum;
import com.kitex.mom.wms.service.impl.WmsLineDeliveryService;
import com.kitex.mom.wms.service.impl.WmsMesIssueOutboxService;
import com.kitex.mom.wms.service.outBill.AbstractWmsOutBillHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class WmsProducePickingOutBillService extends AbstractWmsOutBillHandler {
    private final WmsMesIssueOutboxService issueOutboxService;
    private final WmsLineDeliveryService lineDeliveryService;

    @Override
    public Boolean support(String billType) {
        return OutBillTypeEnum.PRODUCE_OUT.getCode().equals(billType);
    }

    /**
     * 发料与可靠投递事实同事务落库；远端不可用不再导致发料/收料通知跨域半提交。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleDocumentBizWhenAdd(WmsOutBillEntity bill) {
        lineDeliveryService.ensureTask(bill);
        issueOutboxService.enqueue(bill);
    }
}
