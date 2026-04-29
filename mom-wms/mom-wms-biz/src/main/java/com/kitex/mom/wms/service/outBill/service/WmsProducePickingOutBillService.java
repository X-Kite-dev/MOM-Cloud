package com.kitex.mom.wms.service.outBill.service;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mes.api.feign.RemoteWarehousePickingBillService;
import com.kitex.mom.mes.dto.MesPickingWmsOutBillBackDTO;
import com.kitex.mom.mes.entity.MesWarehouseMaterialInBillEntity;
import com.kitex.mom.wms.entity.WmsOutBillDetailEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeDetailEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import com.kitex.mom.wms.enums.DocumentEnum;
import com.kitex.mom.wms.enums.OutBillTypeEnum;
import com.kitex.mom.wms.service.outBill.AbstractWmsOutBillHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class WmsProducePickingOutBillService extends AbstractWmsOutBillHandler {

    private final RemoteWarehousePickingBillService remoteWarehousePickingBillService;

    /**
     * 支持的单据类型
     *
     * @param billType
     */
    @Override
    public Boolean support(String billType) {
        return OutBillTypeEnum.PRODUCE_OUT.getCode().equals(billType);
    }

    /**
     * 处理业务表数据, 出库单完成时修改生产领料单的状态
     */
    @Override
    @Transactional
    public void handleDocumentBizWhenAdd(WmsOutBillEntity bill) {
        WmsOutNoticeEntity notice = wmsOutNoticeMapper.selectById(bill.getNoticeId());
        if (notice == null) {
            return;
        }
        Long documentId = notice.getDocumentId();
        if (documentId == null) {
            return;
        }
        List<WmsOutNoticeDetailEntity> noticeDetails = wmsOutNoticeDetailMapper.selectList(new LambdaQueryWrapper<WmsOutNoticeDetailEntity>()
                .eq(WmsOutNoticeDetailEntity::getNoticeId, notice.getId())
        );
        if (DocumentEnum.MES_PICKING.getCode().equals(notice.getDocumentTable())) {
            List<String> matCodes = noticeDetails.stream().map(WmsOutNoticeDetailEntity::getMatCode).toList();
            List<WmsOutBillDetailEntity> wmsOutBillDetailEntities = wmsOutBillDetailMapper.selectList(new LambdaQueryWrapper<WmsOutBillDetailEntity>()
                    .eq(WmsOutBillDetailEntity::getBillId, bill.getId())
            );
            List<MesWarehouseMaterialInBillEntity> materialDetails = new ArrayList<>();
            for (WmsOutBillDetailEntity item : wmsOutBillDetailEntities) {
                MesWarehouseMaterialInBillEntity material = new MesWarehouseMaterialInBillEntity();
                BeanUtil.copyProperties(item, material, "id", "siteCode", "siteName", "containerCode");
                material.setResourceNo(bill.getBillNo());
                material.setInspectionStatus(item.getQualityResult());
                material.setNoticeInNum(BigDecimal.valueOf(item.getMatNum()));
                materialDetails.add(material);
            }
            // 调用mes接口
            MesPickingWmsOutBillBackDTO dto = new MesPickingWmsOutBillBackDTO();
            dto.setId(documentId);
            dto.setMatCodes(matCodes);
            dto.setMatDetails(materialDetails);
            R<Boolean> booleanR = remoteWarehousePickingBillService.wmsOutBillBack(dto);
            if (!booleanR.isOk()) {
                throw new CheckedException("调用生产领料单接口失败！" + booleanR.getMsg());
            }
        }
    }

}
