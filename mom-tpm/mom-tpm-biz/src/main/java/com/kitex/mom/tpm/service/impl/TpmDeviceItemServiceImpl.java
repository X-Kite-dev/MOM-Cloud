package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.tpm.entity.TpmDeviceItemEntity;
import com.kitex.mom.tpm.mapper.TpmDeviceItemMapper;
import com.kitex.mom.tpm.service.TpmDeviceItemService;
import com.kitex.mom.tpm.service.TpmDeviceMoldRelaService;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 设备-项目绑定表
 *
 * @author kitex
 * @date 2025-02-19 13:41:22
 */
@Service
@AllArgsConstructor
public class TpmDeviceItemServiceImpl extends ServiceImpl<TpmDeviceItemMapper, TpmDeviceItemEntity> implements TpmDeviceItemService {

    private final TpmDeviceMoldRelaService tpmDeviceMoldRelaService;

    @Override
    public Page<TpmDeviceItemEntity> pageList(Page toMpPage, TpmDeviceItemEntity tpmDeviceItem) {
        return this.baseMapper.pageList(toMpPage, tpmDeviceItem);
    }

    @Override
    public List<TpmDeviceItemEntity> getDeviceItemByDeviceCode(String deviceCode, String modeCode, String type) {
        if (ObjectUtil.isEmpty(modeCode)) {
            List<TpmDeviceItemEntity> deviceItemByDeviceCode = this.baseMapper.getDeviceItemByDeviceCode(deviceCode, type);
            if (CollUtil.isEmpty(deviceItemByDeviceCode)) {
                throw new CheckedException("该设备未绑定点/巡检项目配置,请前往“设备台账-编辑-点/巡检项目中进行绑定！”");
            }
            return deviceItemByDeviceCode;
        } else {
            boolean bound = tpmDeviceMoldRelaService.getListByDeviceCodes(List.of(deviceCode)).stream()
                    .anyMatch(relation -> modeCode.equals(relation.getMoldCode()));
            if (bound) {
                List<TpmDeviceItemEntity> deviceItemByDeviceCode = this.baseMapper.getDeviceItemByDeviceCode(modeCode, type);
                if (CollUtil.isEmpty(deviceItemByDeviceCode)) {
                    throw new CheckedException("该模具未绑定点/巡检项目配置,请前往“设备台账-编辑-点/巡检项目中进行绑定！”");
                }
                return deviceItemByDeviceCode;
            } else {
                throw new CheckedException("该模具未绑定至设备【" + deviceCode + "】,请前往“设备台账-编辑-关联模具中进行绑定！”");
            }
        }
    }

    @Override
    public Boolean saveRela(TpmDeviceItemEntity tpmDeviceItem) {
        List<TpmDeviceItemEntity> banding = this.list(new LambdaQueryWrapper<TpmDeviceItemEntity>()
                .eq(TpmDeviceItemEntity::getDeviceCode, tpmDeviceItem.getDeviceCode())
                .eq(TpmDeviceItemEntity::getType, tpmDeviceItem.getType()));
        List<Long> dbItemIdList = banding.stream().map(TpmDeviceItemEntity::getItemId).toList();


        List<Long> itemIdList = tpmDeviceItem.getItemIdList();
        if (CollUtil.isEmpty(itemIdList)) {
            throw new RuntimeException("绑定数据为空！");
        }
        itemIdList = itemIdList.stream().filter(item -> !dbItemIdList.contains(item)).toList();
        List<TpmDeviceItemEntity> results = new ArrayList<>();
        itemIdList.forEach(item -> {
            TpmDeviceItemEntity entity = new TpmDeviceItemEntity();
            BeanUtils.copyProperties(tpmDeviceItem, entity, "id");
            entity.setItemId(item);
            results.add(entity);
        });
        return this.saveBatch(results);
    }
}
