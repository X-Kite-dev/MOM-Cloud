package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.tpm.entity.TpmCheckItemEntity;
import com.kitex.mom.tpm.entity.TpmDeviceItemEntity;
import com.kitex.mom.tpm.mapper.TpmCheckItemMapper;
import com.kitex.mom.tpm.service.TpmCheckItemService;
import com.kitex.mom.tpm.service.TpmDeviceItemService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 点/巡检项目
 *
 * @author kitex
 * @date 2025-02-18 17:40:04
 */
@Service
@AllArgsConstructor
public class TpmCheckItemServiceImpl extends ServiceImpl<TpmCheckItemMapper, TpmCheckItemEntity> implements TpmCheckItemService {

    private final TpmDeviceItemService tpmDeviceItemService;

    @Override
    public Boolean removeItemById(ArrayList<Long> toList) {

        List<TpmDeviceItemEntity> list = tpmDeviceItemService.list(new LambdaQueryWrapper<TpmDeviceItemEntity>()
                .in(TpmDeviceItemEntity::getItemId, toList));
        List<String> codeList = list.stream().map(TpmDeviceItemEntity::getDeviceCode).distinct().toList();
        if (ObjectUtil.isNotEmpty(codeList)) {
            throw new RuntimeException("删除失败，以下设备已绑定所删除的点/巡检项，请先解绑！设备编码:【" + String.join(",", codeList) + "】");
        }
        return this.removeBatchByIds(toList);
    }
}