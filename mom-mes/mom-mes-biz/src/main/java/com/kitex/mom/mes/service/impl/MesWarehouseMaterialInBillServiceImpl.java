package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.admin.api.feign.RemoteParamService;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mes.dto.MesWarehouseLiveStockInoutDTO;
import com.kitex.mom.mes.entity.MesWarehouseMaterialInBillEntity;
import com.kitex.mom.mes.enums.MesMaterialInStateEnum;
import com.kitex.mom.mes.enums.MesWarehouseMatDetailTypeEnum;
import com.kitex.mom.mes.enums.WarehouseInBizTypeEnum;
import com.kitex.mom.mes.enums.YesNoEnum;
import com.kitex.mom.mes.mapper.MesWarehouseMaterialInBillMapper;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import com.kitex.mom.mes.service.MesWarehouseMaterialInBillService;
import com.kitex.mom.mes.vo.MesWarehouseMaterialInBillExcelVO;
import com.kitex.mom.wms.api.feign.RemoteProductionPickingOutBillService;
import com.kitex.mom.wms.entity.WmsOutBillDetailEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 生产领料
 *
 * @author kitex
 * @date 2025-02-24 09:15:17
 */
@Service
@RequiredArgsConstructor
public class MesWarehouseMaterialInBillServiceImpl extends ServiceImpl<MesWarehouseMaterialInBillMapper, MesWarehouseMaterialInBillEntity> implements MesWarehouseMaterialInBillService {

    private final MesWarehouseLiveStockService mesWarehouseLiveStockService;
    private final RemoteSeqConfigService remoteSeqConfigService;
    private final RemoteParamService remoteParamService;
    private final RemoteProductionPickingOutBillService remoteProductionPickingOutBillService;

    /**
     * 新增生产领料-通过出库单
     */
    @Override
    @Transactional
    public Boolean saveByWmsOutBill(List<Long> ids) {
        String isSite = remoteParamService.getByKey("MES_LINE_STOCK_IN_IS_SITE").getData();
        MomUser user = SecurityUtils.getUser();
        String username = user.getUsername();
        String name = user.getName();
        for (Long id : ids) {
            // 获取wms出库单明细
            WmsOutBillEntity outBill = Optional.ofNullable(remoteProductionPickingOutBillService.getDeepById(id).getData()).orElseThrow(() -> new CheckedException("仓库生产领料出库单查找失败"));
            List<WmsOutBillDetailEntity> details = outBill.getDetails();
            // 循环出库单明细赋值并保存
            for (WmsOutBillDetailEntity bill : details) {
                MesWarehouseMaterialInBillEntity mesInBill = new MesWarehouseMaterialInBillEntity();
                BeanUtil.copyProperties(bill, mesInBill, "id", "billNo", "inTime", "warehouseCode", "warehouseName", "siteCode", "siteName", "containerCode");
                mesInBill.setResourceNo(outBill.getBillNo());
                mesInBill.setNoticeInNum(BigDecimal.valueOf(bill.getMatNum()));
                mesInBill.setInspectionStatus(bill.getQualityResult());
                // 序列号
                String billNo = remoteSeqConfigService.create("mes_in_bill").getData();
                mesInBill.setBillNo(billNo);
                mesInBill.setHandleBy(username);
                mesInBill.setHandleByName(name);
                // 状态
                if (YesNoEnum.YES.getCode().equals(isSite)) {
                    mesInBill.setStatus(MesMaterialInStateEnum.PENDING.getCode());
                    save(mesInBill);
                } else {
                    mesInBill.setStatus(MesMaterialInStateEnum.ALREADY.getCode());
                    mesInBill.setInNum(mesInBill.getNoticeInNum());
                    mesInBill.setInTime(LocalDateTime.now());
                    save(mesInBill);
                    // 入库
                    doInStock(mesInBill);
                }
            }
        }
        return Boolean.TRUE;
    }

    @Override
    public Boolean saveRecord(MesWarehouseMaterialInBillEntity inBill) {
        MomUser user = SecurityUtils.getUser();
        String username = user.getUsername();
        String name = user.getName();
        // 序列号
        String billNo = remoteSeqConfigService.create("mes_in_bill").getData();
        inBill.setBillNo(billNo);
        inBill.setHandleBy(username);
        inBill.setHandleByName(name);
        save(inBill);
        // 入库
        doInStock(inBill);
        return null;
    }

    /**
     * 设置库位并入库
     *
     * @param entity
     * @return
     */
    @Override
    @Transactional
    public Boolean inStock(MesWarehouseMaterialInBillEntity entity) {
        BigDecimal inNum = Optional.ofNullable(entity.getInNum()).orElseThrow(() -> new CheckedException("实际入库数量为空！"));
        if (inNum.compareTo(entity.getNoticeInNum()) > 0) {
            throw new CheckedException("超出通知入库数！");
        }
        update(new LambdaUpdateWrapper<MesWarehouseMaterialInBillEntity>()
                .set(MesWarehouseMaterialInBillEntity::getInNum, inNum)
                .set(MesWarehouseMaterialInBillEntity::getSiteCode, entity.getSiteCode())
                .set(MesWarehouseMaterialInBillEntity::getSiteName, entity.getSiteName())
                .set(MesWarehouseMaterialInBillEntity::getInTime, LocalDateTime.now())
                .set(MesWarehouseMaterialInBillEntity::getStatus, MesMaterialInStateEnum.ALREADY.getCode())
                .eq(BaseEntity::getId, entity.getId())
        );
        doInStock(entity);
        return Boolean.TRUE;
    }

    /**
     * 执行入库
     *
     * @param bill 生产领料
     * @return
     */
    @Override
    @Transactional
    public void doInStock(MesWarehouseMaterialInBillEntity bill) {
        MesWarehouseLiveStockInoutDTO liveStock = new MesWarehouseLiveStockInoutDTO();
        BeanUtil.copyProperties(bill, liveStock, "id");
        liveStock.setBillId(bill.getId());
        liveStock.setNum(bill.getInNum());
        liveStock.setBizType(WarehouseInBizTypeEnum.RECEPTION.getCode());
        liveStock.setMatDetailType(MesWarehouseMatDetailTypeEnum.RAW.name());
        mesWarehouseLiveStockService.inStock(liveStock);
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
            vo.setStatus(MesMaterialInStateEnum.getByCode(Optional.ofNullable(vo.getStatus()).orElse(MesMaterialInStateEnum.PENDING.getCode())).getName());
            result.add(vo);
        }
        return result;
    }
}