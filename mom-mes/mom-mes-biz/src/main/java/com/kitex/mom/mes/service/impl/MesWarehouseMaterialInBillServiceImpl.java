package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mes.entity.MesWarehouseMaterialInBillEntity;
import com.kitex.mom.mes.enums.MesMaterialInStateEnum;
import com.kitex.mom.mes.mapper.MesWarehouseMaterialInBillMapper;
import com.kitex.mom.mes.service.MesWarehouseMaterialInBillService;
import com.kitex.mom.mes.vo.MesWarehouseMaterialInBillExcelVO;
import com.kitex.mom.wms.api.feign.RemoteProductionPickingOutBillService;
import com.kitex.mom.wms.entity.WmsOutBillDetailEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.enums.DocumentEnum;
import com.kitex.mom.wms.enums.OutBillTypeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
public class MesWarehouseMaterialInBillServiceImpl extends ServiceImpl<MesWarehouseMaterialInBillMapper, MesWarehouseMaterialInBillEntity> implements MesWarehouseMaterialInBillService {
    private final RemoteProductionPickingOutBillService remoteProductionPickingOutBillService;
    private final MesMaterialReceiptService receiptService;

    /**
     * 导入只生成待签收通知，不再因参数跳过实物交接直接增加线边库存。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveByWmsOutBill(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.contains(null))
            throw new CheckedException("请选择1至100张生产发料出库单");
        for (Long id : ids.stream().distinct().sorted().toList()) {
            R<WmsOutBillEntity> response = remoteProductionPickingOutBillService.getDeepById(id);
            if (response == null || !response.isOk() || response.getData() == null)
                throw new CheckedException("无法核验WMS生产发料出库单");
            WmsOutBillEntity bill = response.getData();
            if (!Objects.equals(id, bill.getId()) || !OutBillTypeEnum.PRODUCE_OUT.getCode().equals(bill.getBillType())
                    || !Set.of("2", "12").contains(StrUtil.blankToDefault(bill.getState(), ""))
                    || bill.getDetails() == null || bill.getDetails().isEmpty())
                throw new CheckedException("只允许从已通过/已完成的生产发料出库单生成收料通知");
            receiptService.requireWriteAccess(bill.getFactoryCode());
            if (bill.getDetails().stream().anyMatch(d -> d == null || d.getId() == null))
                throw new CheckedException("WMS发料明细缺少精确ID");
            for (WmsOutBillDetailEntity detail : bill.getDetails().stream()
                    .sorted(Comparator.comparing(WmsOutBillDetailEntity::getId)).toList()) {
                if (detail.getId() == null || !Objects.equals(detail.getBillId(), id)
                        || detail.getMatNum() == null || !Double.isFinite(detail.getMatNum()) || detail.getMatNum() <= 0)
                    throw new CheckedException("WMS发料明细来源或数量不完整");
                MesWarehouseMaterialInBillEntity notice = new MesWarehouseMaterialInBillEntity();
                BeanUtil.copyProperties(detail, notice, "id", "billId", "inTime", "siteCode", "siteName", "containerCode",
                        "createBy", "createTime", "updateBy", "updateTime", "delFlag");
                notice.setWmsOutDetailId(detail.getId());
                notice.setResourceNo(bill.getBillNo());
                notice.setBillNo(bill.getBillNo());
                notice.setBillId(DocumentEnum.MES_PICKING.getCode().equals(bill.getDocumentTable()) ? bill.getDocumentId() : null);
                notice.setNoticeInNum(BigDecimal.valueOf(detail.getMatNum()));
                notice.setInspectionStatus(detail.getQualityResult());
                notice.setFactoryCode(bill.getFactoryCode());
                receiptService.registerNotice(notice);
            }
            if (DocumentEnum.MES_PICKING.getCode().equals(bill.getDocumentTable()) && bill.getDocumentId() != null)
                receiptService.refreshPicking(bill.getDocumentId());
        }
        return true;
    }

    @Override
    public Boolean saveRecord(MesWarehouseMaterialInBillEntity inBill) {
        throw new CheckedException("生产收料必须来自WMS正式出库通知；请导入来源后按实物签收，不能手填库存事实");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean inStock(MesWarehouseMaterialInBillEntity entity) {
        return receiptService.receiveLegacy(entity);
    }

    @Override
    public void doInStock(MesWarehouseMaterialInBillEntity bill) {
        throw new CheckedException("请使用受控签收命令，禁止绕过签收履历直接入库");
    }

    @Override
    public List<MesWarehouseMaterialInBillExcelVO> listExport(LambdaQueryWrapper<MesWarehouseMaterialInBillEntity> wrapper) {
        List<MesWarehouseMaterialInBillEntity> list = list(wrapper);
        List<MesWarehouseMaterialInBillExcelVO> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            MesWarehouseMaterialInBillEntity entity = list.get(i);
            MesWarehouseMaterialInBillExcelVO vo = new MesWarehouseMaterialInBillExcelVO();
            BeanUtil.copyProperties(entity, vo);
            vo.setRowNo(i + 1);
            MesMaterialInStateEnum status = MesMaterialInStateEnum.getByCode(vo.getStatus());
            vo.setStatus(status == null ? "历史未知状态" : status.getName());
            result.add(vo);
        }
        return result;
    }
}
