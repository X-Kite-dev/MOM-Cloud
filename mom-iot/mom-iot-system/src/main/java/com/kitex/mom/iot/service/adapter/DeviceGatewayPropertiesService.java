package com.kitex.mom.iot.service.adapter;

import com.kitex.mom.iot.entity.IotGatewayEntity;
import com.kitex.mom.iot.gateway.DeviceGatewayProperties;
import com.kitex.mom.iot.gateway.DeviceGatewayPropertiesManager;
import com.kitex.mom.iot.mapper.IotGatewayMapper;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@AllArgsConstructor
public class DeviceGatewayPropertiesService implements DeviceGatewayPropertiesManager {

    private final IotGatewayMapper iotGatewayMapper;


    @Override
    public DeviceGatewayProperties getProperties(String id) {
        IotGatewayEntity gateway = iotGatewayMapper.selectById(id);

        DeviceGatewayProperties build = DeviceGatewayProperties.builder()
                .id(String.valueOf(gateway.getId()))
                .name(gateway.getGatewayCode())
                // 接入方式
                .provider(gateway.getProvider())
                .channelId(String.valueOf(gateway.getNetworkComponentId()))
                .protocol(gateway.getProtocol())
//                .transport(networkComponent.getName())
                .build();
        return build;
    }

    @Override
    public List<DeviceGatewayProperties> getPropertiesByChannel(String channel) {
        return List.of();
    }
}
