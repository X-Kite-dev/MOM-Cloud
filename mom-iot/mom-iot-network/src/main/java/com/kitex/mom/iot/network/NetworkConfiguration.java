package com.kitex.mom.iot.network;

import com.kitex.mom.iot.core.network.NetworkConfigManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class NetworkConfiguration {


    @Bean
    public DefaultNetworkManager networkManager(
            List<NetworkProvider> gatewayProviders,
            NetworkConfigManager networkConfigManager) {

        DefaultNetworkManager defaultNetworkManager = new DefaultNetworkManager(networkConfigManager);
        gatewayProviders.forEach(defaultNetworkManager::register);
        // 批量增加
        return defaultNetworkManager;
    }
}
