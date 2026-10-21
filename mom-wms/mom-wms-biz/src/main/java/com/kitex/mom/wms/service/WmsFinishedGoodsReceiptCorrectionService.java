package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.mom.wms.dto.WmsFinishedGoodsReceiptCorrectionCommand;
import com.kitex.mom.wms.dto.WmsFinishedGoodsReceiptCorrectionView;
import com.kitex.mom.wms.entity.WmsFinishedGoodsReceiptCorrectionEntity;
import com.kitex.mom.wms.entity.WmsFinishedGoodsReceiptCorrectionOperationEntity;

import java.util.List;

/**
 * MES成品实收入库受控红冲业务服务。
 */
public interface WmsFinishedGoodsReceiptCorrectionService
        extends IService<WmsFinishedGoodsReceiptCorrectionEntity> {
    /**
     * 按当前工厂分页查询红冲申请。
     */
    Page<WmsFinishedGoodsReceiptCorrectionEntity> page(SearchPage page,
                                                       WmsFinishedGoodsReceiptCorrectionEntity query);

    /**
     * 依据当前实物和来源账预检一张成品入库通知。
     */
    WmsFinishedGoodsReceiptCorrectionView preview(Long noticeId);

    /**
     * 查询申请、冻结库存和当前事实漂移。
     */
    WmsFinishedGoodsReceiptCorrectionView detail(Long correctionId);

    /**
     * 以永久请求号提交整单红冲申请并冻结证据。
     */
    WmsFinishedGoodsReceiptCorrectionEntity apply(WmsFinishedGoodsReceiptCorrectionCommand command);

    /**
     * 由原申请人撤回待复核申请。
     */
    WmsFinishedGoodsReceiptCorrectionEntity withdraw(Long correctionId,
                                                     WmsFinishedGoodsReceiptCorrectionCommand command);

    /**
     * 由非申请岗位驳回待复核申请。
     */
    WmsFinishedGoodsReceiptCorrectionEntity reject(Long correctionId,
                                                   WmsFinishedGoodsReceiptCorrectionCommand command);

    /**
     * 由非申请岗位重采事实并批准执行WMS实物红冲。
     */
    WmsFinishedGoodsReceiptCorrectionEntity approve(Long correctionId);

    /**
     * 查询不可变操作履历。
     */
    List<WmsFinishedGoodsReceiptCorrectionOperationEntity> operations(Long correctionId);

    /**
     * 将未获MES确认的事件重新放入投递队列。
     */
    int retry(Long correctionId);
}
