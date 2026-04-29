package com.kitex.mom.iot.service.adapter;

import com.kitex.mom.iot.core.network.NetworkConfigManager;
import com.kitex.mom.iot.core.network.NetworkProperties;
import com.kitex.mom.iot.core.network.NetworkType;
import com.kitex.mom.iot.entity.IotNetworkComponentEntity;
import com.kitex.mom.iot.service.IotNetworkComponentService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 网络配置管理器实现
 */
@Component
@AllArgsConstructor
public class LocalNetworkConfigManager implements NetworkConfigManager {

    private final IotNetworkComponentService iotNetworkComponentService;


    @Override
    public List<NetworkProperties> getAllConfigs() {
        List<IotNetworkComponentEntity> list = iotNetworkComponentService.list();
        return list.stream().map(IotNetworkComponentEntity::toNetworkProperties).toList();
    }

    @Override
    public NetworkProperties getConfig(NetworkType networkType, String id) {
        IotNetworkComponentEntity networkComponent = iotNetworkComponentService.getNetworkComponentById(Long.valueOf(id));

        return networkComponent.toNetworkProperties();

    }
}
