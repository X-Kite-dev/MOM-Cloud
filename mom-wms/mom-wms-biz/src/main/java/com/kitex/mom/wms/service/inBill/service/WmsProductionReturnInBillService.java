package com.kitex.mom.wms.service.inBill.service;

import com.kitex.mom.wms.entity.WmsInBillEntity;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.enums.InBillTypeEnum;
import com.kitex.mom.wms.service.impl.WmsMesMaterialReturnReceiptOutboxService;
import com.kitex.mom.wms.service.inBill.AbstractWmsInBillHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 生产退料入库：完成库存记账后，在同一事务冻结MES实收回写事件。
 */
@Component
@RequiredArgsConstructor
public class WmsProductionReturnInBillService extends AbstractWmsInBillHandler {
    private final WmsMesMaterialReturnReceiptOutboxService outboxService;

    @Override
    public Boolean support(String billType) {
        return InBillTypeEnum.RETURN.getCode().equals(billType);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleDocumentBizWhenAdd(WmsInBillEntity bill) {
        if (bill != null && ("2".equals(bill.getState()) || "12".equals(bill.getState()))) {
            WmsInNoticeEntity notice = bill.getNoticeId() == null ? null : wmsInNoticeMapper.selectById(bill.getNoticeId());
            if (notice != null && (notice.getReturnReceiptStatus() == null
                    || "WAITING".equals(notice.getReturnReceiptStatus())
                    || "RECEIVING".equals(notice.getReturnReceiptStatus()))) {
                notice.setReturnReceiptStatus("2".equals(notice.getState()) ? "RECEIVED" : "RECEIVING");
                wmsInNoticeMapper.updateById(notice);
            }
            outboxService.enqueueBill(bill);
        }
    }
}
