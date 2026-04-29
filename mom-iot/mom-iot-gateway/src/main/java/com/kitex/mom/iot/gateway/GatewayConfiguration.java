package com.kitex.mom.iot.gateway;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayConfiguration {


    @Bean
    public DeviceGatewayManager deviceGatewayManager(
            ObjectProvider<DeviceGatewayProvider> gatewayProviders,
            DeviceGatewayPropertiesManager propertiesManager) {

        DefaultDeviceGatewayManager defaultDeviceGatewayManager = new DefaultDeviceGatewayManager(propertiesManager);
        gatewayProviders.forEach(defaultDeviceGatewayManager::register);
        gatewayProviders.forEach(DeviceGatewayProviders::register);
        // 批量增加
        return defaultDeviceGatewayManager;
    }
}
