package com.kitex.mom.wms.service.inNotice.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.pms.api.feign.RemotePurchaseReceiptService;
import com.kitex.mom.pms.dto.WmsPurchaseReceiptRejectDTO;
import com.kitex.mom.pms.entity.WmsPurchaseReceiptBatchDetailEntity;
import com.kitex.mom.wms.entity.WmsInNoticeDetailEntity;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.enums.DocumentEnum;
import com.kitex.mom.wms.enums.InBillTypeEnum;
import com.kitex.mom.wms.service.inNotice.AbstractWmsInNoticeHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class WmsPurchaseInNoticeService extends AbstractWmsInNoticeHandler {

    private final RemotePurchaseReceiptService remotePurchaseReceiptService;

    /**
     * 作废后的回调
     *
     * @param notice
     */
    @Override
    @Transactional
    public void voidObjsBack(WmsInNoticeEntity notice) {
        Long documentId = notice.getDocumentId();
        if (documentId == null) {
            return;
        }
        List<WmsInNoticeDetailEntity> detailEntities = wmsInNoticeDetailMapper.selectList(new LambdaQueryWrapper<WmsInNoticeDetailEntity>()
                .eq(WmsInNoticeDetailEntity::getNoticeId, notice.getId())
        );
        if (DocumentEnum.PURCHASE_RECEIPT.getCode().equals(notice.getDocumentTable())) { // 采购收料单
            String expansion = notice.getExpansion();
            Map<String, Object> map = JSONUtil.toBean(expansion, Map.class);
            String genType = (String) Optional.ofNullable(map.get("genType")).orElseThrow(() -> new CheckedException("入库通知单缺失生成类型属性！"));
            List<WmsPurchaseReceiptBatchDetailEntity> result = new ArrayList<>();
            for (WmsInNoticeDetailEntity billDetail : detailEntities) {
                WmsPurchaseReceiptBatchDetailEntity batchDetail = new WmsPurchaseReceiptBatchDetailEntity();
                BeanUtil.copyProperties(billDetail, batchDetail);
                result.add(batchDetail);
            }
            WmsPurchaseReceiptRejectDTO wmsPurchaseReceiptRejectDTO = new WmsPurchaseReceiptRejectDTO();
            wmsPurchaseReceiptRejectDTO.setDocumentId(documentId);
            wmsPurchaseReceiptRejectDTO.setResult(result);
            wmsPurchaseReceiptRejectDTO.setGenType(genType);
            Boolean data = remotePurchaseReceiptService.voidObjsBack(wmsPurchaseReceiptRejectDTO).getData();
            if (!data) {
                throw new CheckedException("采购收料单回退失败！");
            }
        }
    }

    /**
     * 支持的单据类型
     *
     * @param billType
     */
    @Override
    public Boolean support(String billType) {
        return InBillTypeEnum.PURCHASE.getCode().equals(billType);
    }

}
