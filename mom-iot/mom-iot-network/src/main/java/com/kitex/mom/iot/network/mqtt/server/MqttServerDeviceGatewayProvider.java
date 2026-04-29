package com.kitex.mom.iot.network.mqtt.server;

import com.kitex.mom.iot.core.defaults.DefaultNetworkType;
import com.kitex.mom.iot.core.device.DeviceRegistry;
import com.kitex.mom.iot.core.event.HandleMessageHelpr;
import com.kitex.mom.iot.core.protocol.ProtocolSupport;
import com.kitex.mom.iot.core.protocol.ProtocolSupports;
import com.kitex.mom.iot.gateway.DeviceGateway;
import com.kitex.mom.iot.gateway.DeviceGatewayProperties;
import com.kitex.mom.iot.gateway.DeviceGatewayProvider;
import com.kitex.mom.iot.gateway.Transport;
import com.kitex.mom.iot.network.Network;
import com.kitex.mom.iot.network.NetworkManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Slf4j
@Component
public class MqttServerDeviceGatewayProvider implements DeviceGatewayProvider {

    private final NetworkManager networkManager;

    private final ProtocolSupports protocolSupports;

    private final DeviceRegistry deviceRegistry;

    private final HandleMessageHelpr handleMessageHelpr;

    public MqttServerDeviceGatewayProvider(NetworkManager networkManager, ProtocolSupports protocolSupports, DeviceRegistry deviceRegistry, HandleMessageHelpr handleMessageHelpr) {
        this.networkManager = networkManager;
        this.protocolSupports = protocolSupports;
        this.deviceRegistry = deviceRegistry;
        this.handleMessageHelpr = handleMessageHelpr;
    }

    @Override
    public String getId() {
        return "mqtt-server-gateway";
    }

    @Override
    public String getName() {
        return "MQTT直连接入";
    }

    @Override
    public Transport getTransport() {
        return Transport.MQTT;
    }

    @Override
    public DeviceGateway createDeviceGateway(DeviceGatewayProperties properties) {
        Network network = networkManager.getNetwork(DefaultNetworkType.MQTT_SERVER, properties.getChannelId());
        String protocol = properties.getProtocol();
        ProtocolSupport protocolSupport = protocolSupports.getProtocol(protocol);

        return new MqttServerDeviceGateway(properties.getId(), (MqttServer) network, protocolSupport, deviceRegistry, handleMessageHelpr);
    }


    @Override
    public DeviceGateway reloadDeviceGateway(DeviceGateway gateway, DeviceGatewayProperties properties) {
        MqttServerDeviceGateway deviceGateway = ((MqttServerDeviceGateway) gateway);
        String networkId = properties.getChannelId();
        // 网络组件发生了变化
        if (!Objects.equals(networkId, deviceGateway.server.getId())) {
            gateway.shutdown();
            DeviceGateway newGateway = createDeviceGateway(properties);
            newGateway.startup();
            return newGateway;
        }
        //更新协议包
        deviceGateway.setProtocol(protocolSupports.getProtocol(properties.getProtocol()));
        return deviceGateway;
    }
}
