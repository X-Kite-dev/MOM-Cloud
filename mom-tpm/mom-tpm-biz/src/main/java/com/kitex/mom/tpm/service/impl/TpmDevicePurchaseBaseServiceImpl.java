package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.enums.YesOrNoStateEnum;
import com.kitex.mom.tpm.entity.*;
import com.kitex.mom.tpm.enums.PurchaseNeedStateEnum;
import com.kitex.mom.tpm.enums.PurchaseQcStateEnum;
import com.kitex.mom.tpm.mapper.TpmDevicePurchaseBaseMapper;
import com.kitex.mom.tpm.service.*;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 设备采购单表
 *
 * @author kitex
 * @date 2025-03-26 10:13:32
 */
@Service
@AllArgsConstructor
public class TpmDevicePurchaseBaseServiceImpl extends ServiceImpl<TpmDevicePurchaseBaseMapper, TpmDevicePurchaseBaseEntity> implements TpmDevicePurchaseBaseService {

    private final TpmDevicePurchaseDetailsService tpmDevicePurchaseDetailsService;
    private final TpmDeviceArrivalInspectionService tpmDeviceArrivalInspectionService;
    private final TpmDeviceArrivalInspectionDetailsService tpmDeviceArrivalInspectionDetailsService;
    private final RemoteSeqConfigService remoteSeqConfigService;
    private final TpmDevicePurchaseNeedReviewService tpmDevicePurchaseNeedReviewService;

    @Override
    public TpmDevicePurchaseBaseEntity getDeepById(Long id) {
        TpmDevicePurchaseBaseEntity entity = this.getById(id);
        //获取详情
        List<TpmDevicePurchaseDetailsEntity> detailsEntityList = tpmDevicePurchaseDetailsService.list(new LambdaQueryWrapper<TpmDevicePurchaseDetailsEntity>()
                .eq(TpmDevicePurchaseDetailsEntity::getPurchaseCode, entity.getPurchaseCode()));
        entity.setDetailsList(detailsEntityList);
        return entity;
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean savePurchaseBase(TpmDevicePurchaseBaseEntity tpmDevicePurchaseBase) {
        if (CollUtil.isEmpty(tpmDevicePurchaseBase.getDetailsList())) {
            throw new RuntimeException("请添加采购明细");
        }
        LocalDate now = LocalDate.now();
        //保存逻辑  1.保存主表数据 2.保存明细数据
        tpmDevicePurchaseBase.setPurchaseTime(now);
        String devices = tpmDevicePurchaseBase.getDetailsList().stream().map(TpmDevicePurchaseDetailsEntity::getDeviceName).collect(Collectors.joining(","));
        tpmDevicePurchaseBase.setPurchaseDevice(devices);
        BigDecimal allMoney = tpmDevicePurchaseBase.getDetailsList().stream().map(TpmDevicePurchaseDetailsEntity::getAllMoney).reduce(BigDecimal.ZERO, BigDecimal::add);
        tpmDevicePurchaseBase.setAllMoney(allMoney);
        BigDecimal taxRateMoney = tpmDevicePurchaseBase.getDetailsList().stream().map(TpmDevicePurchaseDetailsEntity::getTaxRateMoney).reduce(BigDecimal.ZERO, BigDecimal::add);
        tpmDevicePurchaseBase.setTaxRateMoney(taxRateMoney);
        tpmDevicePurchaseBase.setState(YesOrNoStateEnum.NO.getCode());
        this.save(tpmDevicePurchaseBase);
        //保存子表
        List<TpmDevicePurchaseDetailsEntity> detailsList = tpmDevicePurchaseBase.getDetailsList();
        detailsList.forEach(item -> {
            item.setPurchaseCode(tpmDevicePurchaseBase.getPurchaseCode());
        });
        tpmDevicePurchaseDetailsService.saveBatch(detailsList);

        List<String> needCodeList = detailsList.stream().map(TpmDevicePurchaseDetailsEntity::getPurchaseNeedCode).distinct().toList();
        tpmDevicePurchaseNeedReviewService.update(new UpdateWrapper<TpmDevicePurchaseNeedReviewEntity>()
                .lambda().in(TpmDevicePurchaseNeedReviewEntity::getPurchaseNeedCode, needCodeList)
                .set(TpmDevicePurchaseNeedReviewEntity::getState, PurchaseNeedStateEnum.CREATE.getCode()));
        return Boolean.TRUE;
    }

    /**
     * 推送质检
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean pushQc(Long id) {
        //更新主表状态
        TpmDevicePurchaseBaseEntity entity = this.getById(id);
        entity.setState(YesOrNoStateEnum.YES.getCode());
        this.updateById(entity);
        //查询明细
        List<TpmDevicePurchaseDetailsEntity> detailsEntityList = tpmDevicePurchaseDetailsService.list(new LambdaQueryWrapper<TpmDevicePurchaseDetailsEntity>()
                .eq(TpmDevicePurchaseDetailsEntity::getPurchaseCode, entity.getPurchaseCode()));

        //保存质检单和明细
        String qcCode = remoteSeqConfigService.create("tpm_device_purchase_qc").getData();
        TpmDeviceArrivalInspectionEntity inspection = new TpmDeviceArrivalInspectionEntity();
        BeanUtils.copyProperties(entity, inspection, "id");
        inspection.setQcCode(qcCode);
        inspection.setState(PurchaseQcStateEnum.WAIT_QC.getCode());
        tpmDeviceArrivalInspectionService.save(inspection);

        List<TpmDeviceArrivalInspectionDetailsEntity> inspectionDetails = detailsEntityList.stream().map(item -> {
            TpmDeviceArrivalInspectionDetailsEntity detailsEntity = new TpmDeviceArrivalInspectionDetailsEntity();
            BeanUtils.copyProperties(item, detailsEntity, "id");
            detailsEntity.setQcCode(qcCode);
            return detailsEntity;
        }).collect(Collectors.toList());

        tpmDeviceArrivalInspectionDetailsService.saveBatch(inspectionDetails);
        return Boolean.TRUE;
    }
}