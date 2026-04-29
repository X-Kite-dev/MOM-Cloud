package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.tpm.entity.TpmDeviceSparePartsRelaEntity;
import com.kitex.mom.tpm.entity.TpmSparePartsEntity;
import com.kitex.mom.tpm.entity.TpmSparePartsStockEntity;
import com.kitex.mom.tpm.mapper.TpmSparePartsMapper;
import com.kitex.mom.tpm.mapper.TpmSparePartsStockMapper;
import com.kitex.mom.tpm.service.TpmDeviceSparePartsRelaService;
import com.kitex.mom.tpm.service.TpmSparePartsService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 备件表
 *
 * @author kitex
 * @date 2025-02-11 16:39:20
 */
@Service
@AllArgsConstructor
public class TpmSparePartsServiceImpl extends ServiceImpl<TpmSparePartsMapper, TpmSparePartsEntity> implements TpmSparePartsService {

    private final TpmSparePartsStockMapper tpmSparePartsStockMapper;
    private final TpmDeviceSparePartsRelaService tpmDeviceSparePartsRelaService;

    @Override
    public Boolean removeSparePartsById(ArrayList<Long> toList) {
        List<TpmSparePartsEntity> sparePartsList = this.listByIds(toList);
        List<String> codeList = sparePartsList.stream().map(TpmSparePartsEntity::getCode).toList();
        //查询库位是否存在备件
        Long aLong = tpmSparePartsStockMapper.selectCount(new LambdaQueryWrapper<TpmSparePartsStockEntity>()
                .in(TpmSparePartsStockEntity::getSparePartsCode, codeList)
                .gt(TpmSparePartsStockEntity::getInventoryNum, BigDecimal.ZERO));
        if (aLong.compareTo(0L) > 0) {
            throw new RuntimeException("备件存在库存信息，请先处理库存中的备件！");
        }
        //查询备件是否被绑定
        List<TpmDeviceSparePartsRelaEntity> bandingList = tpmDeviceSparePartsRelaService.list(new LambdaQueryWrapper<TpmDeviceSparePartsRelaEntity>()
                .in(TpmDeviceSparePartsRelaEntity::getSparePartCode, codeList));
        if (CollUtil.isNotEmpty(bandingList)) {
            List<String> deviceCodeList = bandingList.stream().map(TpmDeviceSparePartsRelaEntity::getDeviceCode).distinct().toList();
            throw new RuntimeException("备件已被绑定，请先解除绑定！绑定的设备编码为【" + String.join(",", deviceCodeList) + "】");
        }
        return this.removeBatchByIds(toList);
    }
}