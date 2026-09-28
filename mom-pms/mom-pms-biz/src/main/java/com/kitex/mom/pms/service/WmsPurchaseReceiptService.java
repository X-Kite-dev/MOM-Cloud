package com.kitex.mom.pms.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.yulichang.extension.mapping.base.MPJDeepService;
import com.kitex.mom.pms.dto.PmsOrderPurchaseReceiptInspectionDTO;
import com.kitex.mom.pms.dto.WmsPurchaseReceiptSetQualifiedNumDTO;
import com.kitex.mom.pms.dto.app.WmsPurchaseReceiptParamsDTO;
import com.kitex.mom.pms.entity.WmsPurchaseReceiptBatchDetailEntity;
import com.kitex.mom.pms.entity.WmsPurchaseReceiptEntity;
import com.kitex.mom.pms.vo.WmsPurchaseReceiptExcelVO;
import com.kitex.mom.qms.dto.inspection.InspectionPurchaseCreateParam;

import java.util.List;

public interface WmsPurchaseReceiptService extends MPJDeepService<WmsPurchaseReceiptEntity> {
    Boolean saveDeep(WmsPurchaseReceiptEntity wmsPurchaseReceipt);

    Boolean updateDeep(WmsPurchaseReceiptEntity wmsPurchaseReceipt);

    Boolean removeDeep(Long[] ids);

    Boolean removeChild(Long[] ids);

    List<WmsPurchaseReceiptExcelVO> listExcel(QueryWrapper<WmsPurchaseReceiptEntity> wrapper);

    /**
     * app收料
     *
     * @param params
     * @return
     */
    Boolean appReceipt(WmsPurchaseReceiptParamsDTO params);

    /**
     * 根据单号获取信息
     */
    WmsPurchaseReceiptEntity getByBillNo(WmsPurchaseReceiptEntity wmsPurchaseReceipt);

    /**
     * 生成质检任务
     */
    Boolean genQualityTask(List<InspectionPurchaseCreateParam> body, Boolean qmsStauts);

    /**
     * 绑定质检任务id
     */
    Boolean setQualityId(WmsPurchaseReceiptEntity body, Boolean qmsStauts);

    /**
     * 质检回馈合格数量
     */
    Boolean setQualifiedNum(WmsPurchaseReceiptSetQualifiedNumDTO body);

    /**
     * 生成采购入库通知单
     */
    Boolean genInNotice(WmsPurchaseReceiptEntity wmsPurchaseReceipt);

    /**
     * 入库时更新实际入库数
     *
     * @param billId   采购收料单id
     * @param matCode  物料编码
     * @param matBatch 物料批次
     * @param matNum   入库数量
     */
    Boolean addMatNum(Long billId, Long orderDetailId, String matCode, String matBatch, Double matNum);

    /**
     * 采购入库通知单作废后回调
     *
     * @param documentId
     * @param genType
     * @param result
     */
    Boolean voidObjsBack(Long documentId, String genType, List<WmsPurchaseReceiptBatchDetailEntity> result);

    /**
     * 收料
     *
     * @param pmsOrder 采购收料单质检DTO
     * @return R
     */
    Boolean receive(PmsOrderPurchaseReceiptInspectionDTO pmsOrder);

    /**
     * 根据id查询采购收料单
     *
     * @param id id
     * @return R  对象
     */
    WmsPurchaseReceiptEntity genQmsDetails(Long id);

    /**
     * 免检生成采购入库通知单
     *
     * @param wmsPurchaseReceipt 采购收料单
     * @return R
     */
    Boolean qmsGenInNotice(WmsPurchaseReceiptEntity wmsPurchaseReceipt);
}
