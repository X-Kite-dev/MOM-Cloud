package com.kitex.mom.qms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.qms.dto.QmsCapaLinkDTO;
import com.kitex.mom.qms.dto.QmsCapaMesRetestReopenCommand;
import com.kitex.mom.qms.entity.QmsCapaMesRetestReopenReceiptEntity;

import java.util.List;

/**
 * MES改善未达标的权威反查、CAPA新周期与永久回执。
 */
public interface QmsCapaMesRetestReopenService extends IService<QmsCapaMesRetestReopenReceiptEntity> {
    /**
     * 同一永久请求号只开启一次周期，重投返回原回执。
     */
    QmsCapaLinkDTO reopen(QmsCapaMesRetestReopenCommand command);

    /**
     * 当前实际租户、工厂内的旧关闭快照及复开历史。
     */
    List<QmsCapaMesRetestReopenReceiptEntity> receipts(Long capaId);
}
