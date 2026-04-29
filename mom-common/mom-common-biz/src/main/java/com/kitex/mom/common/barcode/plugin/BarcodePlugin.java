package com.kitex.mom.common.barcode.plugin;

import com.kitex.mom.common.barcode.dto.req.BarcodeRuleReq;

import java.util.Map;

/**
 * 条码解析插件
 */
public interface BarcodePlugin {

    String getName();

    String getRegular();

    void setTemplate(BarcodeRuleReq barcodeRuleReq);

    /**
     * 接续插件
     *
     * @param barcode 条码
     * @return 解析结果
     */
    Map<String, String> render(String barcode);

}
