package com.kitex.mom.iot.protocol;

import com.kitex.mom.iot.core.protocol.ProtocolSupport;
import com.kitex.mom.iot.core.protocol.ProtocolSupportProvider;
import com.kitex.mom.iot.core.protocol.TransportType;
import com.kitex.mom.iot.protocol.defaults.MqttDeviceMessageCodec;
import com.kitex.mom.iot.protocol.defaults.TcpDeviceMessageCodec;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;


/**
 * 协议配置，后续扩展只需要按照本接口实现即可
 */
@Component
public class DefaultProtocolSupportProvider implements ProtocolSupportProvider {
    @Override
    public ProtocolSupport create() {
        CompositeProtocolSupport support = new CompositeProtocolSupport();
        support.setId("mom-v1");
        support.setName("mom-v1");
        support.setDescription("MOM Protocol Version 3.0");


        // 配置MQTT - 路由
        support.addRoutes(TransportType.MQTT, Arrays
                .stream(TopicMessageCodec.values())
                .map(TopicMessageCodec::getRoute)
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));


        // 配置MQTT - 文档
        support.setDocument(TransportType.MQTT,
                "document-mqtt.md",
                DefaultProtocolSupportProvider.class.getClassLoader());

        // 配置MQTT - 编码解码器
        support.addMessageCodecSupport(new MqttDeviceMessageCodec());


        // 配置TCP - 编码解码器
        support.addMessageCodecSupport(new TcpDeviceMessageCodec());

        // 配置TCP - 文档
        support.setDocument(TransportType.TCP,
                "document-tcp.md",
                DefaultProtocolSupportProvider.class.getClassLoader());


        return support;
    }
}
