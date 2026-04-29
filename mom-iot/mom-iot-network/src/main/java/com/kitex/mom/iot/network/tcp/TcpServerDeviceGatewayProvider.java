package com.kitex.mom.iot.network.tcp;

import com.kitex.mom.iot.core.defaults.DefaultNetworkType;
import com.kitex.mom.iot.core.device.DeviceRegistry;
import com.kitex.mom.iot.core.event.HandleMessageHelpr;
import com.kitex.mom.iot.core.protocol.ProtocolSupport;
import com.kitex.mom.iot.core.protocol.ProtocolSupports;
import com.kitex.mom.iot.gateway.DeviceGateway;
import com.kitex.mom.iot.gateway.DeviceGatewayProperties;
import com.kitex.mom.iot.gateway.DeviceGatewayProvider;
import com.kitex.mom.iot.gateway.Transport;
import com.kitex.mom.iot.network.NetworkManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * TCP服务器设备网关提供商
 */
@Component
@Slf4j
public class TcpServerDeviceGatewayProvider implements DeviceGatewayProvider {

    private final NetworkManager networkManager;

    private final ProtocolSupports protocolSupports;

    private final DeviceRegistry deviceRegistry;

    private final HandleMessageHelpr handleMessageHelpr;

    public TcpServerDeviceGatewayProvider(NetworkManager networkManager, ProtocolSupports protocolSupports, DeviceRegistry deviceRegistry, HandleMessageHelpr handleMessageHelpr) {
        this.networkManager = networkManager;
        this.protocolSupports = protocolSupports;
        this.deviceRegistry = deviceRegistry;
        this.handleMessageHelpr = handleMessageHelpr;
    }


    @Override
    public String getId() {
        return "tcp-server-gateway";
    }

    @Override
    public String getName() {
        return "TCP 透传接入";
    }

    @Override
    public String getDescription() {
        return "基于TCP服务器的设备网关，用于接入TCP设备";
    }

    @Override
    public Transport getTransport() {
        return Transport.TCP;
    }

    @Override
    public DeviceGateway createDeviceGateway(DeviceGatewayProperties properties) {
        TcpServer network = networkManager.<TcpServer>getNetwork(DefaultNetworkType.TCP_SERVER, properties.getChannelId());

        String protocol = properties.getProtocol();
        ProtocolSupport protocolSupport = protocolSupports.getProtocol(protocol);

        // 创建TCP服务器设备网关
        return new TcpServerDeviceGateway(properties.getId(), network, protocolSupport, deviceRegistry, handleMessageHelpr);
    }

    @Override
    public DeviceGateway reloadDeviceGateway(DeviceGateway gateway, DeviceGatewayProperties properties) {
        TcpServerDeviceGateway deviceGateway = ((TcpServerDeviceGateway) gateway);
        //网络组件发生变化
        if (!Objects.equals(deviceGateway.tcpServer.getId(), properties.getChannelId())) {
            gateway.shutdown();
            DeviceGateway newGateway = this.createDeviceGateway(properties);
            newGateway.startup();
            return newGateway;
        }
        //更新协议包
        deviceGateway.setProtocol(protocolSupports.getProtocol(properties.getProtocol()));
        return gateway;
    }
}