package com.kitex.mom.wms.service.inBill.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.pms.api.feign.RemotePurchaseReceiptService;
import com.kitex.mom.pms.dto.WmsPurchaseReceiptInNumDTO;
import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import com.kitex.mom.wms.entity.WmsInBillEntity;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.enums.DocumentEnum;
import com.kitex.mom.wms.enums.InBillTypeEnum;
import com.kitex.mom.wms.service.inBill.AbstractWmsInBillHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class WmsPurchaseInBillService extends AbstractWmsInBillHandler {

    private final RemotePurchaseReceiptService remotePurchaseReceiptService;

    /**
     * 支持的单据类型
     *
     * @param billType
     */
    @Override
    public Boolean support(String billType) {
        return InBillTypeEnum.PURCHASE.getCode().equals(billType);
    }

    /**
     * 处理业务表数据, 入库单完成时修改采购收料单那的数量
     */
    @Override
    public void handleDocumentBizWhenAdd(WmsInBillEntity bill) {
        WmsInNoticeEntity notice = super.wmsInNoticeMapper.selectById(bill.getNoticeId());
        if (notice == null) {
            return;
        }
        Long documentId = notice.getDocumentId();
        if (documentId == null) {
            return;
        }
        List<WmsInBillDetailEntity> billDetails = bill.getDetails();
        if (DocumentEnum.PURCHASE_RECEIPT.getCode().equals(notice.getDocumentTable())) { // 采购收料单
            for (WmsInBillDetailEntity billDetail : billDetails) {
                String matCode = billDetail.getMatCode();
                String matBatch = billDetail.getMatBatch();
                WmsPurchaseReceiptInNumDTO body = new WmsPurchaseReceiptInNumDTO();
                body.setDocumentId(documentId);
                body.setOrderDetailId(billDetail.getSourceOrderDetailId());
                body.setMatCode(matCode);
                body.setMatBatch(matBatch);
                body.setMatNum(billDetail.getMatNum());
                Boolean data = remotePurchaseReceiptService.addMatNum(body).getData();
                if (!data) {
                    throw new CheckedException("采购收料单数量修改失败");
                }
            }
        }
    }

}
