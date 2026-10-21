package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesFinishedGoodsReceiptReverseEvent;
import com.kitex.mom.wms.entity.WmsFinishedGoodsReceiptCorrectionEntity;
import com.kitex.mom.wms.entity.WmsFinishedGoodsReceiptReverseOutboxEntity;

/**
 * WMS成品实物红冲可靠投递箱。
 */
public interface WmsFinishedGoodsReceiptReverseOutboxService
        extends IService<WmsFinishedGoodsReceiptReverseOutboxEntity> {
    /**
     * 在WMS红冲源事务中冻结并写入待投递事件。
     */
    void enqueue(WmsFinishedGoodsReceiptCorrectionEntity correction,
                 MesFinishedGoodsReceiptReverseEvent event);

    /**
     * 跨租户领取到期事件并投递，返回本轮成功确认数。
     */
    int processDue(int limit);

    /**
     * 人工将非成功事件清除死信状态后按原事实续投。
     */
    int resetCorrection(Long correctionId);
}
