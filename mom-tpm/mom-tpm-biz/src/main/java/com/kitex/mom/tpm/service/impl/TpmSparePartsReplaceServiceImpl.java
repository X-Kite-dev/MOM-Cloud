package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.tpm.entity.*;
import com.kitex.mom.tpm.enums.*;
import com.kitex.mom.tpm.event.EventPublisherUtil;
import com.kitex.mom.tpm.mapper.TpmDeviceSparePartsRelaMapper;
import com.kitex.mom.tpm.mapper.TpmSparePartsReplaceMapper;
import com.kitex.mom.tpm.service.TpmSparePartsApplicationService;
import com.kitex.mom.tpm.service.TpmSparePartsReplaceConsumeDetailsService;
import com.kitex.mom.tpm.service.TpmSparePartsReplaceDetailsService;
import com.kitex.mom.tpm.service.TpmSparePartsReplaceService;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * TPM备件更换审批单据表
 *
 * @author kitex
 * @date 2025-03-18 10:27:11
 */
@Service
@AllArgsConstructor
public class TpmSparePartsReplaceServiceImpl extends ServiceImpl<TpmSparePartsReplaceMapper, TpmSparePartsReplaceEntity> implements TpmSparePartsReplaceService {

    private final TpmSparePartsReplaceDetailsService tpmSparePartsReplaceDetailsService;

    private final TpmDeviceSparePartsRelaMapper tpmDeviceSparePartsRelaMapper;

    private final RemoteSeqConfigService remoteSeqConfigService;

    private final TpmSparePartsApplicationService tpmSparePartsApplicationService;

    private final EventPublisherUtil eventPublisherUtil;

    private final TpmSparePartsReplaceConsumeDetailsService tpmSparePartsReplaceConsumeDetailsService;

    @Override
    public TpmSparePartsReplaceEntity getReplaceById(Long id) {
        //查询主表
        TpmSparePartsReplaceEntity byId = this.getById(id);
        //查询子表
        List<TpmSparePartsReplaceDetailsEntity> detailsList = tpmSparePartsReplaceDetailsService.list(new LambdaQueryWrapper<TpmSparePartsReplaceDetailsEntity>()
                .eq(TpmSparePartsReplaceDetailsEntity::getDocSn, byId.getDocSn()));
        byId.setDetailsList(detailsList);
        byId.setUseList(new ArrayList<>());
        return byId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveReplace(TpmSparePartsReplaceEntity tpmSparePartsReplace) {
        //保存主表
        tpmSparePartsReplace.setStatus(SparePartsReplaceStateEnum.WAIT.getCode());
        this.save(tpmSparePartsReplace);
        //保存明细
        List<TpmSparePartsReplaceDetailsEntity> detailsList = tpmSparePartsReplace.getDetailsList();
        detailsList.forEach(detailsEntity -> {
            detailsEntity.setDocSn(tpmSparePartsReplace.getDocSn());
        });
        tpmSparePartsReplaceDetailsService.saveBatch(detailsList);
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean handle(TpmSparePartsReplaceEntity tpmSparePartsReplace) {
        //查询主表
        TpmSparePartsReplaceEntity replaceById = this.getById(tpmSparePartsReplace.getId());
        //查询子表
        List<TpmSparePartsReplaceDetailsEntity> detailsList = tpmSparePartsReplaceDetailsService.list(new LambdaQueryWrapper<TpmSparePartsReplaceDetailsEntity>()
                .eq(TpmSparePartsReplaceDetailsEntity::getDocSn, replaceById.getDocSn()));

        Map<String, TpmSparePartsReplaceDetailsEntity> detailsEntityMap = detailsList.stream().collect(Collectors.toMap(TpmSparePartsReplaceDetailsEntity::getSparePartCode, Function.identity(), (k1, k2) -> k1));

        List<TpmDeviceSparePartsRelaEntity> relaList = tpmDeviceSparePartsRelaMapper.selectList(new LambdaQueryWrapper<TpmDeviceSparePartsRelaEntity>()
                .eq(TpmDeviceSparePartsRelaEntity::getDeviceCode, replaceById.getDeviceCode()));

        for (TpmDeviceSparePartsRelaEntity rela : relaList) {
            TpmSparePartsReplaceDetailsEntity detailsEntity = detailsEntityMap.get(rela.getSparePartCode());
            if (ObjectUtil.isNotEmpty(detailsEntity)) {
                rela.setInstallationDate(LocalDate.now());
            }
        }
        MomUser user = SecurityUtils.getUser();
        replaceById.setStatus(SparePartsReplaceStateEnum.COMPLETE.getCode());
        replaceById.setOperatorId(user.getUsername());
        replaceById.setOperatorName(user.getName());
        replaceById.setReplaceDate(LocalDate.now());
        this.updateById(replaceById);
        tpmDeviceSparePartsRelaMapper.updateById(relaList);

        //备件更换保存生命周期事件记录
        eventPublisherUtil.publishDeviceLifeCycleEvent(entity -> {
            entity.setDeviceCode(replaceById.getDeviceCode());
            entity.setDeviceName(replaceById.getDeviceName());
            entity.setEventCode(replaceById.getDocSn());
            entity.setOperator(user.getName() + "(" + user.getUsername() + ")");
            entity.setEventSource(DeviceLifeCycleEventEnum.REPLACE.getCode());
            entity.setDescription("设备: " + replaceById.getDeviceName() + "【" + replaceById.getDeviceCode() + "】更换备件，详情查看备件更换单：【" + replaceById.getDocSn() + "】");
        });
        List<TpmSparePartsReplaceDetailsEntity> useList = tpmSparePartsReplace.getUseList();
        //保存价格信息（之后）
        //保存备件消耗信息
        List<TpmSparePartsReplaceConsumeDetailsEntity> consumeDetailsEntityList = new ArrayList<>();
        for (TpmSparePartsReplaceDetailsEntity tpmSparePartsReplaceDetailsEntity : useList) {
            TpmSparePartsReplaceConsumeDetailsEntity consumeDetailsEntity = new TpmSparePartsReplaceConsumeDetailsEntity();
            BeanUtils.copyProperties(tpmSparePartsReplaceDetailsEntity, consumeDetailsEntity, "id");
            consumeDetailsEntity.setDocSn(replaceById.getDocSn());
            consumeDetailsEntityList.add(consumeDetailsEntity);
        }
        tpmSparePartsReplaceConsumeDetailsService.saveBatch(consumeDetailsEntityList);

        //比对申请和使用记录生成退库申请
        if (ObjectUtil.isNotEmpty(useList)) {
            //2.查询备件申请数据
            List<TpmSparePartsApplicationDetailsEntity> applyBySourceCode = tpmSparePartsApplicationService.getApplyBySourceCode(tpmSparePartsReplace.getDocSn(), SparePartsApplicationSourceEnum.PLAN.getCode());
            List<TpmSparePartsApplicationDetailsEntity> passApplicationDetailsList = applyBySourceCode.stream().filter(item -> item.getApprovalStatus()
                    .equals(SparePartsApplicationStateEnum.PASS.getCode())).toList();

            Map<String, TpmSparePartsApplicationDetailsEntity> applicationSparePartsMap = applyBySourceCode.stream().collect(Collectors.toMap(TpmSparePartsApplicationDetailsEntity::getSparePartCode, Function.identity(), (k1, k2) -> k1));

            Map<String, BigDecimal> applicationMap = passApplicationDetailsList.stream().collect(Collectors.groupingBy(
                    TpmSparePartsApplicationDetailsEntity::getSparePartCode,
                    Collectors.reducing(BigDecimal.ZERO, TpmSparePartsApplicationDetailsEntity::getQuantityRequested, BigDecimal::add)));

            //3.比对备件申请通过的备件数与备件使用的数据，如果备件没有全部使用，自动生成备件退回单据
            List<TpmSparePartsApplicationDetailsEntity> returnList = new ArrayList<>();
            for (Map.Entry<String, BigDecimal> entry : applicationMap.entrySet()) {
                TpmSparePartsReplaceDetailsEntity entity = useList.stream().filter(item -> item.getSparePartCode().equals(entry.getKey())).findFirst().orElse(null);
                if (ObjectUtil.isEmpty(entity)) {
                    TpmSparePartsApplicationDetailsEntity item = applicationSparePartsMap.get(entry.getKey());
                    returnList.add(buildApply(item, entry.getValue()));
                } else {
                    if (entry.getValue().compareTo(entity.getUseNum()) > 0) {
                        TpmSparePartsApplicationDetailsEntity item = applicationSparePartsMap.get(entry.getKey());
                        BigDecimal subtract = entry.getValue().subtract(entity.getUseNum());
                        returnList.add(buildApply(item, subtract));
                    }
                }

            }
            if (CollUtil.isNotEmpty(returnList)) {
                String applyCode = remoteSeqConfigService.create("spare_parts_application").getData();
                returnList.forEach(item -> {
                    item.setApplyCode(applyCode);
                });
                TpmSparePartsApplicationEntity application = new TpmSparePartsApplicationEntity();
                application.setApplyCode(applyCode);
                application.setType(BillOperateTypeEnum.IN.getCode());
                application.setSourceCode(tpmSparePartsReplace.getDocSn());
                application.setSource(SparePartsApplicationSourceEnum.PLAN_RETURN.getCode());
                application.setRequesterCode(user.getUsername());
                application.setRequesterName(user.getName());
                application.setRequestDate(LocalDate.now());
                application.setApprovalStatus(SparePartsApplicationStateEnum.WAIT.getCode());
                application.setDetailsList(returnList);
                //保存备件退回申请
                tpmSparePartsApplicationService.addApply(application);
            }
        }
        return Boolean.TRUE;
    }

    private TpmSparePartsApplicationDetailsEntity buildApply(TpmSparePartsApplicationDetailsEntity item, BigDecimal quantityRequested) {
        TpmSparePartsApplicationDetailsEntity entity = new TpmSparePartsApplicationDetailsEntity();
        entity.setSparePartCode(item.getSparePartCode());
        entity.setSparePartName(item.getSparePartName());
        entity.setSpecification(item.getSpecification());
        entity.setUnit(item.getUnit());
        entity.setQuantityRequested(quantityRequested);
        return entity;
    }

}