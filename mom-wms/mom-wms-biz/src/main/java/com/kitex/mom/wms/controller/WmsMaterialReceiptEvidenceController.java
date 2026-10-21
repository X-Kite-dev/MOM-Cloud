package com.kitex.mom.wms.controller;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.wms.dto.WmsLineDeliveryEvidenceDTO;
import com.kitex.mom.wms.entity.WmsOutBillDetailEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import com.kitex.mom.wms.mapper.WmsOutBillDetailMapper;
import com.kitex.mom.wms.mapper.WmsOutBillMapper;
import com.kitex.mom.wms.mapper.WmsOutNoticeMapper;
import com.kitex.mom.wms.service.impl.WmsLineDeliveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 只读已提交的数据库事实；MES不能仅凭先前的同步回调认定WMS已成功出库。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsMaterialReceipt/internal")
public class WmsMaterialReceiptEvidenceController {
    private final WmsOutBillDetailMapper detailMapper;
    private final WmsOutBillMapper billMapper;
    private final WmsOutNoticeMapper noticeMapper;
    private final WmsLineDeliveryService lineDeliveryService;

    @Inner
    @GetMapping("/source/{detailId}")
    public R<WmsOutBillEntity> source(@PathVariable Long detailId) {
        WmsOutBillDetailEntity detail = detailMapper.selectById(detailId);
        WmsOutBillEntity bill = detail == null ? null : billMapper.selectById(detail.getBillId());
        if (bill == null) throw new CheckedException("WMS出库来源尚未提交、不存在或已撤销");
        if (bill.getNoticeId() != null) {
            WmsOutNoticeEntity notice = noticeMapper.selectById(bill.getNoticeId());
            if (notice == null) throw new CheckedException("WMS出库通知来源不存在");
            bill.setDocumentId(notice.getDocumentId());
            bill.setDocumentTable(notice.getDocumentTable());
        }
        bill.setDetails(List.of(detail));
        return R.ok(bill);
    }

    @Inner
    @GetMapping("/line-delivery/{detailId}")
    public R<WmsLineDeliveryEvidenceDTO> lineDelivery(@PathVariable Long detailId) {
        return R.ok(lineDeliveryService.evidence(detailId));
    }
}
