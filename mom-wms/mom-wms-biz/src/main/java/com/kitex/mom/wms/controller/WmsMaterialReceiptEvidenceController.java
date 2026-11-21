package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.wms.dto.WmsLineDeliveryEvidenceDTO;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.service.impl.WmsLineDeliveryService;
import com.kitex.mom.wms.service.impl.WmsMaterialReceiptSourceReader;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 只读已提交的数据库事实；MES不能仅凭先前的同步回调认定WMS已成功出库。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsMaterialReceipt/internal")
public class WmsMaterialReceiptEvidenceController {
    private final WmsMaterialReceiptSourceReader sources;
    private final WmsLineDeliveryService lineDeliveryService;

    /** 按实际工厂、原出库行读取已提交的发料凭证。 */
    @Inner
    @GetMapping("/source/{detailId}")
    public R<WmsOutBillEntity> source(@PathVariable Long detailId) {
        return R.ok(sources.source(detailId));
    }

    @Inner
    @GetMapping("/line-delivery/{detailId}")
    public R<WmsLineDeliveryEvidenceDTO> lineDelivery(@PathVariable Long detailId) {
        return R.ok(lineDeliveryService.evidence(detailId));
    }
}
