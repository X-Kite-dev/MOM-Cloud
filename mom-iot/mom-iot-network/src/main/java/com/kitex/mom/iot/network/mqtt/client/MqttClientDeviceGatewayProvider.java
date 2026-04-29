package com.kitex.mom.iot.network.mqtt.client;

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
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class MqttClientDeviceGatewayProvider implements DeviceGatewayProvider {

    private final NetworkManager networkManager;

    private final ProtocolSupports protocolSupports;

    private final DeviceRegistry deviceRegistry;

    private final HandleMessageHelpr handleMessageHelpr;

    public MqttClientDeviceGatewayProvider(NetworkManager networkManager, ProtocolSupports protocolSupports, DeviceRegistry deviceRegistry, HandleMessageHelpr handleMessageHelpr) {
        this.networkManager = networkManager;
        this.protocolSupports = protocolSupports;
        this.deviceRegistry = deviceRegistry;
        this.handleMessageHelpr = handleMessageHelpr;
    }

    @Override
    public String getId() {
        return "mqtt-client-gateway";
    }

    @Override
    public String getName() {
        return "MQTT Broker接入";
    }

    @Override
    public Transport getTransport() {
        return Transport.MQTT;
    }

    @Override
    public DeviceGateway createDeviceGateway(DeviceGatewayProperties properties) {
        Network network = networkManager.getNetwork(DefaultNetworkType.MQTT_CLIENT, properties.getChannelId());
        String protocol = properties.getProtocol();

        ProtocolSupport protocolSupport = protocolSupports.getProtocol(protocol);


        return new MqttClientDeviceGateway(properties.getId(), (MqttBrokerClient) network, protocolSupport, deviceRegistry, handleMessageHelpr);
    }


    @Override
    public DeviceGateway reloadDeviceGateway(DeviceGateway gateway, DeviceGatewayProperties properties) {
        MqttClientDeviceGateway deviceGateway = ((MqttClientDeviceGateway) gateway);
        String networkId = properties.getChannelId();
        if (!Objects.equals(networkId, deviceGateway.mqttBrokerClient.getId())) {
            gateway.shutdown();
            DeviceGateway newGateway = createDeviceGateway(properties);
            newGateway.startup();
            return newGateway;
        }
        // 更新协议
        return gateway;
    }
}
