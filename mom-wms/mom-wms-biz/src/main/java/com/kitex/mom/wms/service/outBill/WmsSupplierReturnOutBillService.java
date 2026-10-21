package com.kitex.mom.wms.service.outBill;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.entity.WmsOutBillDetailEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeDetailEntity;
import com.kitex.mom.wms.enums.OtherOutBillTypeEnum;
import com.kitex.mom.wms.service.impl.WmsPmsSupplierReturnOutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 采购退供专用出库处理器：完成库存扣减后只写可靠Outbox。
 */
@Component
@RequiredArgsConstructor
public class WmsSupplierReturnOutBillService extends AbstractWmsOutBillHandler {
    private final WmsPmsSupplierReturnOutboxService outboxService;

    @Override
    public Boolean support(String billType) {
        return OtherOutBillTypeEnum.OTHER_PMS.getCode().equals(billType);
    }

    /**
     * 退供通知已经由PMS异岗复核并冻结精确库存。WMS可以分批实物出库，
     * 但不能增删通知行、替换库存行、物料、仓库或库位。
     */
    @Override
    public void checkData(WmsOutBillEntity bill) {
        super.checkData(bill);
        require(bill.getNoticeId() != null, "采购退供出库必须来源于已批准的WMS通知");
        Set<Long> noticeDetailIds = new HashSet<>();
        for (WmsOutBillDetailEntity detail : bill.getDetails()) {
            require(detail.getNoticeDetailId() != null && detail.getSourceStockId() != null,
                    "采购退供出库必须携带通知明细和精确库存主键");
            require(noticeDetailIds.add(detail.getNoticeDetailId()), "同一退供通知明细不能在一张出库单中重复");
            WmsOutNoticeDetailEntity frozen = wmsOutNoticeDetailMapper.selectById(detail.getNoticeDetailId());
            require(frozen != null && Objects.equals(frozen.getNoticeId(), bill.getNoticeId())
                            && Objects.equals(frozen.getSourceStockId(), detail.getSourceStockId())
                            && Objects.equals(frozen.getMatCode(), detail.getMatCode())
                            && Objects.equals(frozen.getWarehouseCode(), detail.getWarehouseCode()),
                    "采购退供出库行与批准的通知、库存、物料或仓库不一致");
        }
    }

    @Override
    public void handleDocumentBizWhenAdd(WmsOutBillEntity bill) {
        outboxService.enqueueIfCompleted(bill);
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
