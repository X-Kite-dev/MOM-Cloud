package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.qms.dto.QmsFinishedGoodsDecisionReversalExecutionEvent;
import com.kitex.mom.wms.entity.WmsFinishedGoodsReceiptCorrectionEntity;
import com.kitex.mom.wms.entity.WmsQmsFinishedGoodsDecisionReversalOutboxEntity;

/**
 * WMS实物红冲结果向QMS可靠回送、重试和永久回执管理。
 */
public interface WmsQmsFinishedGoodsDecisionReversalOutboxService
        extends IService<WmsQmsFinishedGoodsDecisionReversalOutboxEntity> {
    void enqueue(WmsFinishedGoodsReceiptCorrectionEntity correction,
                 QmsFinishedGoodsDecisionReversalExecutionEvent event);

    int processDue(int limit);

    int resetCorrection(Long correctionId);
}
