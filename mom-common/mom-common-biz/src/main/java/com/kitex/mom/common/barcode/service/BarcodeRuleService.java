package com.kitex.mom.common.barcode.service;

import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.common.barcode.dto.req.BarcodeRuleReq;
import com.kitex.mom.common.barcode.entity.BarcodeRuleEntity;

public interface BarcodeRuleService extends IService<BarcodeRuleEntity> {

    Boolean saveTemplate(BarcodeRuleReq barcodeRuleReq);

    BarcodeRuleReq getInfoById(Long id);

    Boolean updateById(BarcodeRuleReq barcodeRuleReq);

    Object rander(String code);

    String genBarcode(JSONObject jsonObject, String barcodeRuleCode);

}