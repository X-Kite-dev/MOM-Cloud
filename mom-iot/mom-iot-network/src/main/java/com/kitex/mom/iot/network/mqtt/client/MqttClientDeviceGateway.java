package com.kitex.mom.iot.network.mqtt.client;

import com.kitex.mom.iot.core.codec.DeviceMessageCodec;
import com.kitex.mom.iot.core.device.DeviceRegistry;
import com.kitex.mom.iot.core.event.HandleMessageHelpr;
import com.kitex.mom.iot.core.message.DeviceMessage;
import com.kitex.mom.iot.core.message.FromDeviceMessageContext;
import com.kitex.mom.iot.core.protocol.ProtocolSupport;
import com.kitex.mom.iot.core.protocol.TransportType;
import com.kitex.mom.iot.core.route.MqttRoute;
import com.kitex.mom.iot.gateway.AbstractDeviceGateway;
import com.kitex.mom.iot.protocol.utils.TopicUtils;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttException;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class MqttClientDeviceGateway extends AbstractDeviceGateway {

    /**
     * -- GETTER --
     * 获取MQTT客户端
     *
     * @return MqttBrokerClient
     */
    @Getter
    final MqttBrokerClient mqttBrokerClient;
    private final DeviceRegistry registry;
    private final HandleMessageHelpr handleMessageHelpr;
    private final Map<String, Integer> subscriptions = new ConcurrentHashMap<>();
    private ProtocolSupport protocol;
    private DeviceMessageCodec codecMono;

    /**
     * 构造函数
     *
     * @param id 网关ID
     */
    public MqttClientDeviceGateway(String id, MqttBrokerClient client, ProtocolSupport protocol, DeviceRegistry registry, HandleMessageHelpr handleMessageHelpr) {
        super(id);
        this.mqttBrokerClient = Objects.requireNonNull(client, "mqttClient");
        this.handleMessageHelpr = handleMessageHelpr;
        setProtocol(protocol);
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    private void setProtocol(ProtocolSupport protocol) {
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.codecMono = protocol.getCodec(TransportType.MQTT);
    }

    @Override
    protected void doShutdown() {
        if (mqttBrokerClient.isConnected()) {
            try {
                // 取消所有订阅
                for (String topic : subscriptions.keySet()) {
                    mqttBrokerClient.unsubscribe(topic);
                }
                subscriptions.clear();

                // 断开连接
                mqttBrokerClient.disconnect();
                log.info("MQTT客户端网关[{}]已断开连接", getId());
            } catch (MqttException e) {
                log.error("MQTT客户端网关[{}]断开连接失败", getId(), e);
            }
        }
    }

    @SneakyThrows
    @Override
    protected void doStartup() {
        if (mqttBrokerClient == null) {
            throw new IllegalStateException("MQTT客户端未配置");
        }

        mqttBrokerClient.connect();
        protocol.getRoutes(TransportType.MQTT).stream().filter(route -> route instanceof MqttRoute).map(MqttRoute.class::cast).filter(MqttRoute::isUpstream).forEach(route -> {
            String topic = convertToMqttTopic(route.getTopic());
            int qos = route.getQos();
            try {
                mqttBrokerClient.subscribe(topic, qos);
            } catch (MqttException e) {
                throw new RuntimeException(e);
            }
        });
        mqttBrokerClient.setMessageHandler(msg -> {
            log.info("收的消息-》 {}", msg.getPayload());
            DeviceMessage deviceMessage = codecMono.decode(FromDeviceMessageContext.of(
                    msg,
                    registry
            ));
            // 设备中的信息
            log.info("获取设备转码之后的信息 -》 {}", deviceMessage.getProperties());
            // 发送消息到
            handleMessageHelpr.handleMessage(deviceMessage);

        });

        log.info("MQTT客户端网关[{}]启动成功，已连接到: {}", getId(), mqttBrokerClient.getBrokerUrl());
    }

    private String convertToMqttTopic(String topic) {
        return TopicUtils.convertToMqttTopic(topic);
    }


    /**
     * 发布消息
     *
     * @param topic    主题
     * @param payload  消息内容
     * @param qos      服务质量
     * @param retained 是否保留
     */
    public void publish(String topic, byte[] payload, int qos, boolean retained) {
        if (!mqttBrokerClient.isConnected()) {
            try {
                mqttBrokerClient.connect();
            } catch (MqttException e) {
                log.error("MQTT客户端网关[{}]连接失败", getId(), e);
                throw new RuntimeException("MQTT客户端连接失败", e);
            }
        }

        try {
            mqttBrokerClient.publish(topic, payload, qos, retained);
            log.debug("MQTT客户端网关[{}]已发布消息: topic={}, qos={}, payload={}bytes", getId(), topic, qos, payload.length);
        } catch (MqttException e) {
            log.error("MQTT客户端网关[{}]发布消息失败: {}", getId(), topic, e);
            throw new RuntimeException("发布消息失败", e);
        }
    }

}
