package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.feign.RemoteDeviceService;
import com.kitex.mom.tpm.entity.TpmDeviceSparePartsRelaEntity;
import com.kitex.mom.tpm.entity.TpmSparePartsEntity;
import com.kitex.mom.tpm.entity.TpmSparePartsReplaceDetailsEntity;
import com.kitex.mom.tpm.entity.TpmSparePartsReplaceEntity;
import com.kitex.mom.tpm.mapper.TpmDeviceSparePartsRelaMapper;
import com.kitex.mom.tpm.mapper.TpmSparePartsMapper;
import com.kitex.mom.tpm.service.TpmDeviceSparePartsRelaService;
import com.kitex.mom.tpm.service.TpmSparePartsReplaceService;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * TPM设备-备件关联表
 *
 * @author kitex
 * @date 2025-03-18 10:00:49
 */
@Service
@AllArgsConstructor
public class TpmDeviceSparePartsRelaServiceImpl extends ServiceImpl<TpmDeviceSparePartsRelaMapper, TpmDeviceSparePartsRelaEntity> implements TpmDeviceSparePartsRelaService {

    private final RemoteDeviceService remoteDeviceService;
    private final RemoteSeqConfigService remoteSeqConfigService;
    private final TpmSparePartsReplaceService tpmSparePartsReplaceService;
    private final TpmSparePartsMapper tpmSparePartsMapper;

    /**
     * 分页查询 - 根据设备编码
     */
    @Override
    public Page<TpmDeviceSparePartsRelaEntity> getTpmDeviceSparePartsRelaPage(Page toMpPage, TpmDeviceSparePartsRelaEntity tpmDeviceSparePartsRela) {
        return this.baseMapper.getTpmDeviceSparePartsRelaPage(toMpPage, tpmDeviceSparePartsRela);
    }

    /**
     * 根据id查询
     *
     * @param id id
     */
    @Override
    public TpmDeviceSparePartsRelaEntity getRelaById(Long id) {
        return this.baseMapper.getRelaById(id);
    }

    /**
     * 保存关联
     *
     * @param tpmDeviceSparePartsRela 实体
     */
    @Override
    public Boolean saveRela(TpmDeviceSparePartsRelaEntity tpmDeviceSparePartsRela) {

        List<TpmDeviceSparePartsRelaEntity> dbRelaList = this.list(new LambdaQueryWrapper<TpmDeviceSparePartsRelaEntity>()
                .eq(TpmDeviceSparePartsRelaEntity::getDeviceCode, tpmDeviceSparePartsRela.getDeviceCode()));

        Map<String, TpmDeviceSparePartsRelaEntity> dbPartsMap = dbRelaList.stream().collect(Collectors.toMap(TpmDeviceSparePartsRelaEntity::getSparePartCode, Function.identity(), (a, b) -> a));
        //保存为一对多,如果存在则更新，不存在则新增
        List<String> codeList = Arrays.stream(tpmDeviceSparePartsRela.getSparePartCode().split(",")).toList();
        List<TpmDeviceSparePartsRelaEntity> results = new ArrayList<>();
        for (String code : codeList) {
            TpmDeviceSparePartsRelaEntity dbEntity = dbPartsMap.get(code);
            if (ObjectUtil.isNotNull(dbEntity)) {
                dbEntity.setReplacementInterval(tpmDeviceSparePartsRela.getReplacementInterval());
                dbEntity.setInstallationDate(tpmDeviceSparePartsRela.getInstallationDate());
                results.add(dbEntity);
            } else {
                TpmDeviceSparePartsRelaEntity entity = new TpmDeviceSparePartsRelaEntity();
                BeanUtils.copyProperties(tpmDeviceSparePartsRela, entity, "id");
                entity.setSparePartCode(code);
                results.add(entity);
            }
        }
        return this.saveOrUpdateBatch(results);
    }

    /**
     * 创建申请
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean createApply(ArrayList<Long> idList) {
        //查询关联表
        List<TpmDeviceSparePartsRelaEntity> tpmDeviceSparePartsRelaEntities = this.listByIds(idList);
        //查询设备
        TpmDeviceSparePartsRelaEntity relaEntity = tpmDeviceSparePartsRelaEntities.get(0);
        MdmDeviceEntity mdmDevice = new MdmDeviceEntity();
        mdmDevice.setDeviceCode(relaEntity.getDeviceCode());
        MdmDeviceEntity data = remoteDeviceService.getDetails(mdmDevice).getData();
        if (ObjectUtil.isEmpty(data)) {
            throw new RuntimeException("访问设备数据失败，请授权或者检查主数据服务是否运行！");
        }
        //查询备件
        List<TpmSparePartsEntity> sparePartsEntityList = tpmSparePartsMapper.selectList(new LambdaQueryWrapper<>());
        Map<String, TpmSparePartsEntity> sparePartsMap = sparePartsEntityList.stream().collect(Collectors.toMap(TpmSparePartsEntity::getCode, Function.identity(), (a, b) -> a));

        String replaceCode = remoteSeqConfigService.create("spare_parts_replace").getData();
        MomUser user = SecurityUtils.getUser();
        //构建主表
        TpmSparePartsReplaceEntity tpmSparePartsReplaceEntity = buildReplace(data, replaceCode, user);
        //构建子表
        List<TpmSparePartsReplaceDetailsEntity> detailsList = new ArrayList<>();
        tpmDeviceSparePartsRelaEntities.forEach(item -> {
            TpmSparePartsEntity partsEntity = sparePartsMap.get(item.getSparePartCode());
            TpmSparePartsReplaceDetailsEntity detailsEntity = new TpmSparePartsReplaceDetailsEntity();
            detailsEntity.setDocSn(replaceCode);
            detailsEntity.setSparePartCode(partsEntity.getCode());
            detailsEntity.setSparePartName(partsEntity.getName());
            detailsEntity.setSpecification(partsEntity.getSpec());
            detailsEntity.setUnit(partsEntity.getUnit());
            detailsList.add(detailsEntity);
        });
        tpmSparePartsReplaceEntity.setDetailsList(detailsList);
        tpmSparePartsReplaceService.saveReplace(tpmSparePartsReplaceEntity);
        return Boolean.TRUE;
    }

    private TpmSparePartsReplaceEntity buildReplace(MdmDeviceEntity data, String replaceCode, MomUser user) {
        TpmSparePartsReplaceEntity tpmSparePartsReplaceEntity = new TpmSparePartsReplaceEntity();
        tpmSparePartsReplaceEntity.setDocSn(replaceCode);
        tpmSparePartsReplaceEntity.setDeviceCode(data.getDeviceCode());
        tpmSparePartsReplaceEntity.setDeviceName(data.getDeviceName());
        tpmSparePartsReplaceEntity.setApplicantId(user.getUsername());
        tpmSparePartsReplaceEntity.setApplicantName(user.getName());
        tpmSparePartsReplaceEntity.setApplicationDate(LocalDate.now());
        return tpmSparePartsReplaceEntity;
    }


}