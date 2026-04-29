//package com.kitex.mom.iot.network.http;
//
//import com.kitex.mom.iot.gateway.DeviceGateway;
//import com.kitex.mom.iot.gateway.DeviceGatewayProperties;
//import com.kitex.mom.iot.gateway.DeviceGatewayProvider;
//import com.kitex.mom.iot.gateway.Transport;
//import com.kitex.mom.iot.core.defaults.DefaultNetworkType;
//import com.kitex.mom.iot.network.NetworkManager;
//import com.kitex.mom.iot.core.protocol.ProtocolSupport;
//import com.kitex.mom.iot.core.protocol.ProtocolSupports;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.stereotype.Component;
//
//import java.util.Objects;
//
/// **
// * http 网关提供上
// */
//@Slf4j
//@Component
//public class HttpServerDeviceGatewayProvider implements DeviceGatewayProvider {
//
//    private final NetworkManager networkManager;
//
//    private final ProtocolSupports protocolSupports;
//
//    public HttpServerDeviceGatewayProvider(NetworkManager networkManager, ProtocolSupports protocolSupports) {
//        this.networkManager = networkManager;
//        this.protocolSupports = protocolSupports;
//    }
//
//
//    @Override
//    public String getId() {
//        return "http-server-gateway";
//    }
//
//    @Override
//    public String getName() {
//        return "Http 推送接入";
//    }
//
//    @Override
//    public Transport getTransport() {
//        return Transport.HTTP;
//    }
//
//    @Override
//    public DeviceGateway createDeviceGateway(DeviceGatewayProperties properties) {
//        HttpServer network = networkManager.getNetwork(DefaultNetworkType.HTTP_SERVER, properties.getChannelId());
//
//        // 获取该网关使用的协议
//        String protocol = properties.getProtocol();
//        ProtocolSupport protocolSupport = protocolSupports.getProtocol(protocol);
//
//        return new HttpServerDeviceGateway(properties.getId(), network,protocolSupport);
//    }
//
//
//    @Override
//    public DeviceGateway reloadDeviceGateway(DeviceGateway gateway, DeviceGatewayProperties properties) {
//        HttpServerDeviceGateway deviceGateway = ((HttpServerDeviceGateway) gateway);
//        String networkId = properties.getChannelId();
//        if (!Objects.equals(networkId, deviceGateway.server.getId())) {
//            gateway.shutdown();
//            DeviceGateway newGateway = createDeviceGateway(properties);
//            newGateway.startup();
//            return newGateway;
//        }
//        //更新协议包
//        //deviceGateway.setProtocol(protocolSupports.getProtocol(properties.getProtocol()));
//        return deviceGateway;
//
//    }
//}
