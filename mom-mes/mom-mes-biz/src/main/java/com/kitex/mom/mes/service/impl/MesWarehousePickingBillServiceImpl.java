package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mes.dto.MesPickingReceiveMatDTO;
import com.kitex.mom.mes.dto.MesPickingWmsOutBillBackDTO;
import com.kitex.mom.mes.entity.MesWarehouseMaterialInBillEntity;
import com.kitex.mom.mes.entity.MesWarehousePickingBillDetailEntity;
import com.kitex.mom.mes.entity.MesWarehousePickingBillEntity;
import com.kitex.mom.mes.enums.MesMaterialInStateEnum;
import com.kitex.mom.mes.enums.MesPickingBillStateEnum;
import com.kitex.mom.mes.enums.YesNoEnum;
import com.kitex.mom.mes.mapper.MesWarehousePickingBillDetailMapper;
import com.kitex.mom.mes.mapper.MesWarehousePickingBillMapper;
import com.kitex.mom.mes.service.MesWarehouseMaterialInBillService;
import com.kitex.mom.mes.service.MesWarehousePickingBillService;
import com.kitex.mom.wms.api.feign.RemoteProducePickingNoticeService;
import com.kitex.mom.wms.entity.WmsOutNoticeDetailEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import com.kitex.mom.wms.enums.DocumentEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 生产领料
 *
 * @author kitex
 * @date 2025-02-24 09:15:17
 */
@Service
@RequiredArgsConstructor
public class MesWarehousePickingBillServiceImpl extends ServiceImpl<MesWarehousePickingBillMapper, MesWarehousePickingBillEntity> implements MesWarehousePickingBillService {

    private final MesWarehousePickingBillDetailMapper mesWarehousePickingBillDetailMapper;
    private final MesWarehouseMaterialInBillService mesWarehouseMaterialInBillService;
    private final RemoteSeqConfigService remoteSeqConfigService;
    private final RemoteProducePickingNoticeService remoteProducePickingNoticeService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeep(MesWarehousePickingBillEntity entity) {
        // 序列号
        R<String> billNoR = remoteSeqConfigService.create("mes_picking_bill");
        if (!billNoR.isOk()) {
            throw new CheckedException("生成生产领料单号失败！");
        }
        String billNo = billNoR.getData();
        entity.setBillNo(billNo);
        entity.setStatus(MesPickingBillStateEnum.CREATED.getCode());
        MomUser user = SecurityUtils.getUser();
        entity.setPicker(user.getUsername());
        entity.setPickTime(LocalDateTime.now());
        baseMapper.insert(entity);

        for (MesWarehousePickingBillDetailEntity detail : entity.getDetailList()) {
            detail.setBillId(entity.getId());
            detail.setBillNo(billNo);
            detail.setStatus(YesNoEnum.NO.getCode());
            mesWarehousePickingBillDetailMapper.insert(detail);
        }

        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDeep(MesWarehousePickingBillEntity entity) {
        baseMapper.updateById(entity);
        for (MesWarehousePickingBillDetailEntity mesWarehouseLockDetail : entity.getDetailList()) {
            if (Objects.isNull(mesWarehouseLockDetail.getId())) {
                mesWarehouseLockDetail.setBillId(entity.getId());
                mesWarehouseLockDetail.setBillId(entity.getId());
                mesWarehousePickingBillDetailMapper.insert(mesWarehouseLockDetail);
            } else {
                mesWarehousePickingBillDetailMapper.updateById(mesWarehouseLockDetail);
            }
        }
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeDeep(Long[] ids) {
        baseMapper.deleteBatchIds(CollUtil.toList(ids));
        mesWarehousePickingBillDetailMapper.delete(Wrappers.<MesWarehousePickingBillDetailEntity>lambdaQuery().in(MesWarehousePickingBillDetailEntity::getBillId, ids));
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeChild(Long[] ids) {
        mesWarehousePickingBillDetailMapper.deleteBatchIds(CollUtil.toList(ids));
        return Boolean.TRUE;
    }

    /**
     * 提交生产领料单
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean commitBill(Long id) {
        MomUser user = SecurityUtils.getUser();
        String username = user.getUsername();
        // 1、修改状态
        update(new LambdaUpdateWrapper<MesWarehousePickingBillEntity>()
                        .set(MesWarehousePickingBillEntity::getStatus, MesPickingBillStateEnum.COMMITTED.getCode())
//				.set(MesWarehousePickingBillEntity::getPicker, username)
//				.set(MesWarehousePickingBillEntity::getPickTime, LocalDateTime.now())
                        .eq(BaseEntity::getId, id)
        );
        MesWarehousePickingBillEntity pickingBill = getByIdDeep(id);
        // 2、生成出库通知单
        List<MesWarehousePickingBillDetailEntity> detailList = pickingBill.getDetailList();
        // 明细中存在不同的出库仓库，需要生成不同的出库单
        Map<String, List<MesWarehousePickingBillDetailEntity>> map = detailList.stream().collect(Collectors.groupingBy(MesWarehousePickingBillDetailEntity::getWarehouseCode));
        for (String warehouseCode : map.keySet()) {
            List<MesWarehousePickingBillDetailEntity> list = map.get(warehouseCode);
            String warehouseName = list.get(0).getWarehouseName();
            WmsOutNoticeEntity outNotice = new WmsOutNoticeEntity();
            BeanUtil.copyProperties(pickingBill, outNotice, "id");
            outNotice.setWarehouseCode(warehouseCode);
            outNotice.setWarehouseName(warehouseName);
            outNotice.setDocumentId(id);
            outNotice.setDocumentTable(DocumentEnum.MES_PICKING.getCode());
            List<WmsOutNoticeDetailEntity> outNoticeDetails = new ArrayList<>();
            for (MesWarehousePickingBillDetailEntity pickingBillDetail : list) {
                WmsOutNoticeDetailEntity outNoticeDetail = new WmsOutNoticeDetailEntity();
                BeanUtil.copyProperties(pickingBillDetail, outNoticeDetail, "id");
                outNoticeDetail.setNoticeOutNum(pickingBillDetail.getMatNum().doubleValue());
                outNoticeDetail.setDocumentId(id);
                outNoticeDetail.setDocumentTable(DocumentEnum.MES_PICKING.getCode());
                outNoticeDetails.add(outNoticeDetail);
            }
            outNotice.setDetailList(outNoticeDetails);
            R<Boolean> add = remoteProducePickingNoticeService.add(outNotice);
            if (!add.isOk()) {
                throw new CheckedException("生成WMS领料出库通知单失败！" + add.getMsg());
            }
        }
        return Boolean.TRUE;
    }

    /**
     * 撤回
     *
     * @param id 生产领料单id
     */
    @Override
    @Transactional
    public Boolean withdraw(Long id) {
//		MesWarehousePickingBillEntity pickingBill = getByIdDeep(id);
        // 作废出库通知单
        R voidRes = remoteProducePickingNoticeService.voidObjByDocumentId(id, DocumentEnum.MES_PICKING.getCode());
        if (!voidRes.isOk()) {
            throw new CheckedException("作废领料出库通知单失败！" + voidRes.getMsg());
        }
        // 修改状态
        update(new LambdaUpdateWrapper<MesWarehousePickingBillEntity>()
                .set(MesWarehousePickingBillEntity::getStatus, MesPickingBillStateEnum.CREATED.getCode())
                .eq(BaseEntity::getId, id)
        );
        return Boolean.TRUE;
    }

    /**
     * 领料出库单完成后返回结果
     */
    @Override
    @Transactional
    public Boolean wmsOutBillBack(MesPickingWmsOutBillBackDTO dto) {
        Long id = dto.getId();
        List<String> matCodes = dto.getMatCodes();
        List<MesWarehouseMaterialInBillEntity> matDetails = dto.getMatDetails();
        MesWarehousePickingBillEntity billEntity = getById(id);
        if (CollUtil.isNotEmpty(matCodes)) {
            mesWarehousePickingBillDetailMapper.update(new LambdaUpdateWrapper<MesWarehousePickingBillDetailEntity>()
                    .set(MesWarehousePickingBillDetailEntity::getStatus, YesNoEnum.YES.getCode())
                    .in(MesWarehousePickingBillDetailEntity::getMatCode, matCodes)
                    .eq(MesWarehousePickingBillDetailEntity::getBillId, id)
            );
        }
        Long count = mesWarehousePickingBillDetailMapper.selectCount(new LambdaQueryWrapper<MesWarehousePickingBillDetailEntity>()
                .eq(MesWarehousePickingBillDetailEntity::getStatus, YesNoEnum.NO.getCode())
                .eq(MesWarehousePickingBillDetailEntity::getBillId, id)
        );
        // 全部明细拣货完成后主表状态改为待接收
        if (count == 0) {
            update(new LambdaUpdateWrapper<MesWarehousePickingBillEntity>()
                    .set(MesWarehousePickingBillEntity::getStatus, MesPickingBillStateEnum.PENDING.getCode())
                    .eq(BaseEntity::getId, id)
            );
        }
        // 保存物料明细
        for (MesWarehouseMaterialInBillEntity matDetail : matDetails) {
            matDetail.setBillId(id);
            matDetail.setBillNo(billEntity.getBillNo());
            matDetail.setStatus(MesMaterialInStateEnum.PENDING.getCode());
            matDetail.setFactoryCode(billEntity.getFactoryCode());
        }
        mesWarehouseMaterialInBillService.saveBatch(matDetails);
        return Boolean.TRUE;
    }

    @Override
    @Transactional
    public Boolean receiveBill(MesPickingReceiveMatDTO dto) {
        Long id = dto.getId();
        MesWarehousePickingBillEntity pickingBill = getById(id);
        List<MesWarehouseMaterialInBillEntity> list = mesWarehouseMaterialInBillService.list(new LambdaQueryWrapper<MesWarehouseMaterialInBillEntity>()
                .eq(MesWarehouseMaterialInBillEntity::getBillId, id)
        );
        for (MesWarehouseMaterialInBillEntity material : list) {
            material.setStatus(MesMaterialInStateEnum.ALREADY.getCode());
            material.setInTime(LocalDateTime.now());
            material.setInNum(material.getNoticeInNum());
            material.setSiteCode(dto.getSiteCode());
            material.setSiteName(dto.getSiteName());
            material.setFactoryCode(pickingBill.getFactoryCode());
        }
        mesWarehouseMaterialInBillService.updateBatchById(list);
        for (MesWarehouseMaterialInBillEntity material : list) {
            mesWarehouseMaterialInBillService.doInStock(material);
        }
        // 更改状态
        update(new LambdaUpdateWrapper<MesWarehousePickingBillEntity>()
                .set(MesWarehousePickingBillEntity::getStatus, MesPickingBillStateEnum.FINISHED.getCode())
                .eq(BaseEntity::getId, id)
        );
        return Boolean.TRUE;
    }
}