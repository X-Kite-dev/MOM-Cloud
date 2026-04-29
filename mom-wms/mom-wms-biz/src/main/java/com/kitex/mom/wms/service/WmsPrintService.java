package com.kitex.mom.wms.service;

import com.kitex.mom.common.barcode.dto.req.PrintReq;

/**
 * 出/入库通知单打印接口
 *
 * @author kitex
 * @date 2025-01-24 11:29:11
 */
public interface WmsPrintService {

    /**
     * 入库通知单打印
     *
     * @param printReq 打印Req
     * @return R 对象
     */
    String inNoticePrint(PrintReq printReq);

    /**
     * 出库通知单打印
     *
     * @param printReq 打印Req
     * @return R 对象
     */
    String outNoticePrint(PrintReq printReq);
}
