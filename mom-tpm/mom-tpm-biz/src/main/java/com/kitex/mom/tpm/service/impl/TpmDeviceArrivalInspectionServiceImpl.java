package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.util.R;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.enums.DeviceStateEnum;
import com.kitex.mom.mdm.enums.YesOrNoStateEnum;
import com.kitex.mom.mdm.feign.RemoteDeviceService;
import com.kitex.mom.tpm.entity.TpmDeviceArrivalInspectionDetailsEntity;
import com.kitex.mom.tpm.entity.TpmDeviceArrivalInspectionEntity;
import com.kitex.mom.tpm.entity.TpmDeviceArrivalInspectionRecordEntity;
import com.kitex.mom.tpm.enums.DeviceLifeCycleEventEnum;
import com.kitex.mom.tpm.enums.PurchaseQcStateEnum;
import com.kitex.mom.tpm.event.EventPublisherUtil;
import com.kitex.mom.tpm.mapper.TpmDeviceArrivalInspectionMapper;
import com.kitex.mom.tpm.service.TpmDeviceArrivalInspectionDetailsService;
import com.kitex.mom.tpm.service.TpmDeviceArrivalInspectionRecordService;
import com.kitex.mom.tpm.service.TpmDeviceArrivalInspectionService;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 设备到货质检表
 *
 * @author kitex
 * @date 2025-03-26 10:21:32
 */
@Service
@AllArgsConstructor
public class TpmDeviceArrivalInspectionServiceImpl extends ServiceImpl<TpmDeviceArrivalInspectionMapper, TpmDeviceArrivalInspectionEntity> implements TpmDeviceArrivalInspectionService {

    private final TpmDeviceArrivalInspectionDetailsService tpmDeviceArrivalInspectionDetailsService;
    private final TpmDeviceArrivalInspectionRecordService tpmDeviceArrivalInspectionRecordService;
    private final RemoteSeqConfigService remoteSeqConfigService;
    private final RemoteDeviceService remoteDeviceService;
    private final EventPublisherUtil eventPublisherUtil;

    @Override
    public TpmDeviceArrivalInspectionEntity getDeepById(Long id) {
        //查询主表数据
        TpmDeviceArrivalInspectionEntity entity = this.getById(id);
        List<TpmDeviceArrivalInspectionDetailsEntity> detailsEntityList = tpmDeviceArrivalInspectionDetailsService.list(new LambdaQueryWrapper<TpmDeviceArrivalInspectionDetailsEntity>()
                .eq(TpmDeviceArrivalInspectionDetailsEntity::getQcCode, entity.getQcCode()));
        //查询子表数据
        entity.setDetailsList(detailsEntityList);
        return entity;
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean approval(TpmDeviceArrivalInspectionEntity entity) {
        MomUser user = SecurityUtils.getUser();
        LocalDateTime now = LocalDateTime.now();
        //查询主表
        TpmDeviceArrivalInspectionEntity byId = this.getById(entity.getId());
        if (YesOrNoStateEnum.YES.getCode().equals(entity.getType())) {
            //通过逻辑 1. 更新主表信息  2.创建质检单 3.生成设备
            byId.setQcPersonCode(user.getUsername());
            byId.setQcPersonName(user.getName());
            byId.setQcTime(now);
            byId.setState(PurchaseQcStateEnum.PASS.getCode());
            //生成设备
            //查询子表
            List<TpmDeviceArrivalInspectionDetailsEntity> detailsList = entity.getDetailsList();

            R<List<MdmDeviceEntity>> allMdmDeviceList = remoteDeviceService.getAllMdmDeviceList();
            if (allMdmDeviceList.getCode() != 0) {
                throw new RuntimeException("获取设备数据失败，请检查主数据服务是否运行！");
            }
            List<MdmDeviceEntity> deviceListData = allMdmDeviceList.getData();
            List<String> dbDeviceCodeList = deviceListData.stream().map(MdmDeviceEntity::getDeviceCode).collect(Collectors.toList());

            List<MdmDeviceEntity> mdmDeviceEntities = new ArrayList<>();
            for (TpmDeviceArrivalInspectionDetailsEntity detailsEntity : detailsList) {
                List<String> deviceCodeList = new ArrayList<>();
                if (detailsEntity.getPurchaseNum().compareTo(1) > 0) {
                    for (int i = 0; i < detailsEntity.getPurchaseNum(); i++) {
                        mdmDeviceEntities.add(buildDeviceEntity(byId, detailsEntity, "more", i + 1, dbDeviceCodeList, deviceCodeList));
                    }
                } else {
                    mdmDeviceEntities.add(buildDeviceEntity(byId, detailsEntity, "once", null, dbDeviceCodeList, deviceCodeList));

                }
                detailsEntity.setDeviceCode(String.join(",", deviceCodeList));
            }

            R<Boolean> booleanR = remoteDeviceService.saveBatch(mdmDeviceEntities);
            if (booleanR.getCode() != 0) {
                throw new RuntimeException("保存设备数据失败，请检查主数据服务是否运行！");
            }

            for (MdmDeviceEntity mdmDeviceEntity : mdmDeviceEntities) {
                eventPublisherUtil.publishDeviceLifeCycleEvent(item -> {
                    item.setDeviceCode(mdmDeviceEntity.getDeviceCode());
                    item.setDeviceName(mdmDeviceEntity.getDeviceName());
                    item.setEventCode(entity.getQcCode());
                    item.setOperator(user.getName() + "(" + user.getUsername() + ")");
                    item.setEventSource(DeviceLifeCycleEventEnum.ADD.getCode());
                    item.setDescription("设备: " + mdmDeviceEntity.getDeviceName() + "【" + mdmDeviceEntity.getDeviceCode() + "】采购质检审批通过，详情查看质检单【" + entity.getQcCode() + "】");
                });
            }
            tpmDeviceArrivalInspectionDetailsService.updateBatchById(detailsList);
        }
        if (YesOrNoStateEnum.NO.getCode().equals(entity.getType())) {
            // 不通过逻辑 1. 更新主表状态不通过，以及驳回原因  2.创建质检单
            byId.setQcPersonCode(user.getUsername());
            byId.setQcPersonName(user.getName());
            byId.setQcTime(now);
            byId.setRejectReason(entity.getRejectReason());
            byId.setState(PurchaseQcStateEnum.REJECT.getCode());
        }
        //更新主表信息
        this.updateById(byId);
        //创建质检单记录
        TpmDeviceArrivalInspectionRecordEntity recordEntity = new TpmDeviceArrivalInspectionRecordEntity();
        BeanUtils.copyProperties(byId, recordEntity, "id");
        tpmDeviceArrivalInspectionRecordService.save(recordEntity);
        return Boolean.TRUE;
    }

    private String getDeviceCode() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        LocalDateTime now = LocalDateTime.now();
        String format = now.format(formatter);
        String deviceCode = remoteSeqConfigService.create("tpm_device_code").getData();
        return deviceCode.replace(format, "");
    }


    private MdmDeviceEntity buildDeviceEntity(TpmDeviceArrivalInspectionEntity byId,
                                              TpmDeviceArrivalInspectionDetailsEntity detailsEntity,
                                              String type, Integer num,
                                              List<String> dbDeviceCodeList,
                                              List<String> deviceCodeList) {
        MdmDeviceEntity entity = new MdmDeviceEntity();

        if ("once".equals(type)) {
            if (StrUtil.isNotBlank(detailsEntity.getDeviceCode())) {
                String deviceCode = detailsEntity.getDeviceCode();
                if (dbDeviceCodeList.contains(deviceCode)) {
                    throw new RuntimeException("数据库存在重复的设备编码:【" + deviceCode + "】");
                }
                entity.setDeviceCode(deviceCode);
                //加入集合中,防止刚入库设备列表编码重复
                dbDeviceCodeList.add(deviceCode);
            } else {
                String deviceCode = getDeviceCode();
                entity.setDeviceCode(deviceCode);
                dbDeviceCodeList.add(deviceCode);
            }
            entity.setPurchasePrice(detailsEntity.getAllMoney());

        }
        if ("more".equals(type)) {
            if (StrUtil.isNotBlank(detailsEntity.getDeviceCode())) {
                String deviceCode = detailsEntity.getDeviceCode() + "-00" + num;
                while (dbDeviceCodeList.contains(deviceCode)) {
                    num++;
                    deviceCode = detailsEntity.getDeviceCode() + "-00" + num;
                }
                entity.setDeviceCode(deviceCode);
                dbDeviceCodeList.add(deviceCode);
            } else {
                String deviceCode = getDeviceCode();
                entity.setDeviceCode(deviceCode);
                dbDeviceCodeList.add(deviceCode);
            }
            BigDecimal divide = detailsEntity.getAllMoney().divide(BigDecimal.valueOf(detailsEntity.getPurchaseNum()), new MathContext(2, RoundingMode.HALF_UP));
            entity.setPurchasePrice(divide);
        }
        entity.setDeviceName(detailsEntity.getDeviceName());
        entity.setSpec(detailsEntity.getDeviceSpec());
        entity.setModel(detailsEntity.getDeviceModel());
        entity.setManufacturer(byId.getSupplierName());
        entity.setClassCode(detailsEntity.getClassCode());
        entity.setClassName(detailsEntity.getClassName());
        entity.setDeviceUsable(YesOrNoStateEnum.YES.getCode());
        entity.setDeviceState(DeviceStateEnum.IDLE.getCode());
        entity.setStatus(true);

        deviceCodeList.add(entity.getDeviceCode());
        return entity;
    }


}

