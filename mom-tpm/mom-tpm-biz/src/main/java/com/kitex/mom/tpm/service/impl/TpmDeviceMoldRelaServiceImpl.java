package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.feign.RemoteDeviceService;
import com.kitex.mom.tpm.entity.TpmDeviceMoldRelaEntity;
import com.kitex.mom.tpm.mapper.TpmDeviceMoldRelaMapper;
import com.kitex.mom.tpm.service.TpmDeviceMoldRelaService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

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
public class TpmDeviceMoldRelaServiceImpl extends ServiceImpl<TpmDeviceMoldRelaMapper, TpmDeviceMoldRelaEntity> implements TpmDeviceMoldRelaService {

    private final RemoteDeviceService remoteDeviceService;

    @Override
    public Page<TpmDeviceMoldRelaEntity> getTpmDeviceMoldRelaPage(Page toMpPage, TpmDeviceMoldRelaEntity tpmDeviceMoldRela) {
        LambdaQueryWrapper<TpmDeviceMoldRelaEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(TpmDeviceMoldRelaEntity::getDeviceCode, tpmDeviceMoldRela.getDeviceCode());
        Page<TpmDeviceMoldRelaEntity> page = this.page(toMpPage, queryWrapper);
        if (CollUtil.isNotEmpty(page.getRecords())) {
            R<List<MdmDeviceEntity>> allMdmDeviceList = remoteDeviceService.getAllMdmDeviceList();
            if (allMdmDeviceList.getCode() != 0) {
                throw new RuntimeException("获取设备数据失败，请检查主数据服务是否运行！");
            }
            List<MdmDeviceEntity> mdmDeviceList = allMdmDeviceList.getData();
            Map<String, MdmDeviceEntity> mdmDeviceEntityMap = mdmDeviceList.stream().collect(Collectors.toMap(MdmDeviceEntity::getDeviceCode, Function.identity(), (a, b) -> a));
            page.getRecords().forEach(item -> {
                MdmDeviceEntity mdmDeviceEntity = mdmDeviceEntityMap.get(item.getMoldCode());
                if (mdmDeviceEntity != null) {
                    item.setMoldName(mdmDeviceEntity.getDeviceName());
                    item.setSpec(mdmDeviceEntity.getSpec());
                    item.setModel(mdmDeviceEntity.getModel());
                    item.setClassCode(mdmDeviceEntity.getClassCode());
                    item.setClassName(mdmDeviceEntity.getClassName());
                }
            });
        }
        return page;
    }

    @Override
    public Boolean saveRela(TpmDeviceMoldRelaEntity tpmDeviceMoldRelaEntity) {
        //批量保存
        List<String> codeList = Arrays.asList(tpmDeviceMoldRelaEntity.getMoldCode().split(","));
        List<TpmDeviceMoldRelaEntity> results = new ArrayList<>();
        for (String code : codeList) {
            TpmDeviceMoldRelaEntity entity = new TpmDeviceMoldRelaEntity();
            entity.setDeviceCode(tpmDeviceMoldRelaEntity.getDeviceCode());
            entity.setMoldCode(code);
            results.add(entity);
        }
        return this.saveBatch(results);
    }

}