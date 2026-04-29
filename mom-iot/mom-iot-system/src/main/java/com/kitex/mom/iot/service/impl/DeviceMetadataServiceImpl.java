package com.kitex.mom.iot.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.iot.dto.DeviceMetadataQueryDTO;
import com.kitex.mom.iot.entity.IotDeviceEntity;
import com.kitex.mom.iot.entity.IotDevicePropertyEntity;
import com.kitex.mom.iot.entity.IotProductModelEntity;
import com.kitex.mom.iot.mapper.IotDeviceMapper;
import com.kitex.mom.iot.mapper.IotDevicePropertyMapper;
import com.kitex.mom.iot.mapper.IotProductModelMapper;
import com.kitex.mom.iot.service.DeviceMetadataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 设备元数据服务实现
 *
 * @author system
 */
@Service
@RequiredArgsConstructor
public class DeviceMetadataServiceImpl implements DeviceMetadataService {

    private final IotDevicePropertyMapper devicePropertyMapper;
    private final IotProductModelMapper productModelMapper;
    private final IotDeviceMapper deviceMapper;

    @Override
    public IPage<IotDevicePropertyEntity> queryMetadata(Page<IotDevicePropertyEntity> page, DeviceMetadataQueryDTO queryDTO) {
        // 验证必要参数
        Assert.notNull(queryDTO.getProductId(), "产品ID不能为空");
        Assert.notNull(queryDTO.getModelIdentifier(), "物模型标识符不能为空");
        Assert.notNull(page, "分页参数不能为空");

        // 如果没有指定设备，则查询该产品下的所有设备
        List<Long> deviceIds = queryDTO.getDeviceIds();
        if (CollUtil.isEmpty(deviceIds)) {
            deviceIds = deviceMapper.selectByProductId(queryDTO.getProductId())
                    .stream().map(IotDeviceEntity::getId).collect(Collectors.toList());
        }

        // 如果没有设备，返回空结果
        if (CollUtil.isEmpty(deviceIds)) {
            page.setRecords(Collections.emptyList());
            page.setTotal(0);
            return page;
        }

        // 查询设备属性数据
        return devicePropertyMapper.selectMetadataPage(
                page,
                deviceIds,
                queryDTO.getModelIdentifier(),
                queryDTO.getStartTime(),
                queryDTO.getEndTime()
        );
    }

    @Override
    public List<IotProductModelEntity> getProductModels(Long productId) {
        Assert.notNull(productId, "产品ID不能为空");
        return productModelMapper.selectByProductId(productId);
    }

    @Override
    public List<IotDeviceEntity> getProductDevices(Long productId) {
        Assert.notNull(productId, "产品ID不能为空");
        return deviceMapper.selectByProductId(productId);
    }
} 