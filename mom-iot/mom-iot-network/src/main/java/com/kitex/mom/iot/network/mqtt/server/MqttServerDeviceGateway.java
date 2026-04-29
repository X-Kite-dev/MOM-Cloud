package com.kitex.mom.iot.network.mqtt.server;

import com.kitex.mom.iot.core.codec.DeviceMessageCodec;
import com.kitex.mom.iot.core.device.DeviceRegistry;
import com.kitex.mom.iot.core.event.HandleMessageHelpr;
import com.kitex.mom.iot.core.message.DeviceMessage;
import com.kitex.mom.iot.core.message.FromDeviceMessageContext;
import com.kitex.mom.iot.core.protocol.ProtocolSupport;
import com.kitex.mom.iot.core.protocol.TransportType;
import com.kitex.mom.iot.gateway.AbstractDeviceGateway;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

@Slf4j
public class MqttServerDeviceGateway extends AbstractDeviceGateway {
    final MqttServer server;
    private final DeviceRegistry registry;
    private final HandleMessageHelpr handleMessageHelpr;
    private ProtocolSupport protocol;
    private DeviceMessageCodec codecMono;

    /**
     * 构造函数
     *
     * @param id              网关ID
     * @param protocolSupport
     */
    public MqttServerDeviceGateway(String id, MqttServer server, ProtocolSupport protocolSupport, DeviceRegistry registry, HandleMessageHelpr handleMessageHelpr) {
        super(id);
        this.server = server;
        setProtocol(protocolSupport);
        this.registry = registry;
        this.handleMessageHelpr = handleMessageHelpr;
    }

    public void setProtocol(ProtocolSupport protocol) {
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.codecMono = protocol.getCodec(TransportType.MQTT);
    }

    @Override
    protected void doShutdown() {
        log.info("MQTT服务器网关[{}]关闭", getId());
        // 网络组件由NetworkManager管理，这里不主动关闭
    }

    @Override
    protected void doStartup() {
        if (server == null) {
            throw new IllegalStateException("MQTT服务器未配置");
        }
        server.setMessageListener(msg -> {
            log.info("收到消息：{}", msg);
            DeviceMessage deviceMessage = codecMono.decode(FromDeviceMessageContext.of(
                    msg,
                    registry
            ));
            // 设备中的信息
            log.info("获取设备转码之后的信息 -》 {}", deviceMessage.getProperties());
            // 发送消息到
            handleMessageHelpr.handleMessage(deviceMessage);
        });

//        if (!server.isAlive()) {
//            throw new IllegalStateException("MQTT服务器未启动");
//        }

        log.info("MQTT服务器网关[{}]启动，连接到MQTT服务: {}:{}",
                getId(), server.getConfig().getHost(), server.getConfig().getPort());
    }

    /**
     * 获取MQTT服务器实例
     *
     * @return MQTT服务器实例
     */
    public MqttServer getMqttServer() {
        return server;
    }

    /**
     * 发布消息到MQTT主题
     *
     * @param topic   主题
     * @param payload 消息内容
     * @param qos     服务质量
     * @param retain  是否保留
     */
    public void publish(String topic, byte[] payload, int qos, boolean retain) {
        if (server != null && server.isAlive()) {
            server.publish(topic, payload, qos, retain);
        }
    }
}
