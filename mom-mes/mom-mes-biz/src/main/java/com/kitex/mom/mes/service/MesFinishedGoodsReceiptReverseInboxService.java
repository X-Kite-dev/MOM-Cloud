package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesFinishedGoodsReceiptReverseEvent;
import com.kitex.mom.mes.entity.MesFinishedGoodsReceiptReverseInboxEntity;

/**
 * WMS成品实物红冲的MES永久收件箱服务。
 */
public interface MesFinishedGoodsReceiptReverseInboxService
        extends IService<MesFinishedGoodsReceiptReverseInboxEntity> {
    /**
     * 幂等接收WMS事件并原子恢复MES、APS和PLAN数量。
     */
    MesFinishedGoodsReceiptReverseEvent.Receipt receive(MesFinishedGoodsReceiptReverseEvent event);

    /**
     * 按永久事件号查询已保存的MES确认回执。
     */
    MesFinishedGoodsReceiptReverseEvent.Receipt receipt(String eventId);
}
