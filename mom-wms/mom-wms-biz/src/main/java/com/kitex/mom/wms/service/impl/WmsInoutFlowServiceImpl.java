package com.kitex.mom.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.wms.dto.app.WmsAllocationBillAppDTO;
import com.kitex.mom.wms.entity.WmsInoutFlowEntity;
import com.kitex.mom.wms.enums.InBillTypeEnum;
import com.kitex.mom.wms.enums.InOutEnum;
import com.kitex.mom.wms.enums.OutBillTypeEnum;
import com.kitex.mom.wms.mapper.WmsInoutFlowMapper;
import com.kitex.mom.wms.service.WmsInoutFlowService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

/**
 * 实时库存
 *
 * @author kitex
 * @date 2024-12-18 14:31:45
 */
@Service
@RequiredArgsConstructor
public class WmsInoutFlowServiceImpl extends ServiceImpl<WmsInoutFlowMapper, WmsInoutFlowEntity> implements WmsInoutFlowService {

    @Override
    public void inOutFlow(WmsAllocationBillAppDTO wmsAllocationBillAdd) {
        WmsInoutFlowEntity out = new WmsInoutFlowEntity();
        BeanUtils.copyProperties(wmsAllocationBillAdd, out);
        out.setId(null);
        out.setWarehouseName(wmsAllocationBillAdd.getOldWarehouseName());
        out.setWarehouseCode(wmsAllocationBillAdd.getOldWarehouseCode());
        out.setSiteName(wmsAllocationBillAdd.getOldSiteName());
        out.setSiteCode(wmsAllocationBillAdd.getOldSiteCode());
        out.setBillNo(wmsAllocationBillAdd.getBillNo());
        out.setContainerCode(wmsAllocationBillAdd.getOldContainerCode());
        out.setOperateType(InOutEnum.OUT.getCode());
        out.setMatNum(wmsAllocationBillAdd.getOperateNum());
        out.setFactoryCode(wmsAllocationBillAdd.getFactoryCode());
        out.setBillType(OutBillTypeEnum.ALLOCATION_OUT.getCode());
        this.save(out);

        WmsInoutFlowEntity in = new WmsInoutFlowEntity();
        BeanUtils.copyProperties(wmsAllocationBillAdd, in);
        in.setId(null);
        in.setWarehouseName(wmsAllocationBillAdd.getNewWarehouseName());
        in.setWarehouseCode(wmsAllocationBillAdd.getNewWarehouseCode());
        in.setSiteName(wmsAllocationBillAdd.getNewSiteName());
        in.setSiteCode(wmsAllocationBillAdd.getNewSiteCode());
        in.setBillNo(wmsAllocationBillAdd.getBillNo());
        in.setContainerCode(wmsAllocationBillAdd.getNewContainerCode());
        in.setOperateType(InOutEnum.IN.getCode());
        in.setMatNum(wmsAllocationBillAdd.getOperateNum());
        in.setFactoryCode(wmsAllocationBillAdd.getFactoryCode());
        in.setBillType(InBillTypeEnum.ALLOCATION.getCode());
        this.save(in);
    }
}