package com.kitex.mom.iot.service.adapter;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.mom.iot.core.device.*;
import com.kitex.mom.iot.entity.IotDeviceEntity;
import com.kitex.mom.iot.mapper.IotDeviceMapper;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class DeviceRegistryService implements DeviceRegistry {
    private final IotDeviceMapper iotDeviceMapper;

    @Override
    public DeviceOperator getDevice(String deviceId) {
        IotDeviceEntity deviceByCode = iotDeviceMapper.selectOne(Wrappers.<IotDeviceEntity>lambdaQuery()
                .eq(IotDeviceEntity::getDeviceCode, deviceId));
        return null;
    }

    @Override
    public DeviceProductOperator getProduct(String productId) {
        return null;
    }

    @Override
    public DeviceOperator register(DeviceInfo deviceInfo) {
        return null;
    }

    @Override
    public DeviceProductOperator register(ProductInfo productInfo) {
        return null;
    }
}
