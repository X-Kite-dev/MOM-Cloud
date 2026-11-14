package com.kitex.mom.iot.network.mqtt.client;

import com.kitex.mom.iot.core.defaults.DefaultNetworkType;
import com.kitex.mom.iot.core.message.codec.SimpleMqttMessage;
import com.kitex.mom.iot.network.AbstractNetwork;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * MQTT客户端网络组件
 */
@Slf4j
public class MqttBrokerClient extends AbstractNetwork {

    private final Map<String, Integer> topicSubscriptions = new ConcurrentHashMap<>();
    @Getter
    private String brokerUrl;
    @Getter
    private String clientId;
    @Getter
    private String port;
    @Getter
    private String username;
    @Getter
    private String password;
    @Getter
    private int keepAliveInterval = 60;
    @Getter
    private boolean cleanSession = true;
    @Getter
    private int connectionTimeout = 30;
    private MqttClient mqttClient;
    private MqttConnectOptions connectOptions;
    @Setter
    private Consumer<SimpleMqttMessage> messageHandler;

    /**
     * 构造函数
     *
     * @param id 网络组件ID
     */
    public MqttBrokerClient(String id) {
        super(id, DefaultNetworkType.MQTT_CLIENT);
    }

    /**
     * 设置MQTT Broker连接参数
     *
     * @param brokerUrl MQTT服务器URL
     * @param clientId  客户端ID
     * @param username  用户名
     * @param password  密码
     */
    public void setConnectionParams(String brokerUrl, String port, String clientId, String username, String password) {
        this.brokerUrl = brokerUrl;
        this.port = port;
        this.clientId = clientId;
        this.username = username;
        this.password = password;
    }

    /**
     * 设置MQTT连接选项
     *
     * @param keepAliveInterval 保持连接间隔时间（秒）
     * @param cleanSession      是否清除会话
     * @param connectionTimeout 连接超时时间（秒）
     */
    public void setConnectionOptions(int keepAliveInterval, boolean cleanSession, int connectionTimeout) {
        this.keepAliveInterval = keepAliveInterval;
        this.cleanSession = cleanSession;
        this.connectionTimeout = connectionTimeout;
    }

    /**
     * 初始化并连接到MQTT broker
     *
     * @throws MqttException MQTT异常
     */
    public void connect() throws MqttException {
        if (mqttClient != null && mqttClient.isConnected()) {
            log.info("MQTT客户端已连接: {}", getId());
            return;
        }

        try {
            // 使用MemoryPersistence作为客户端持久化方式
            MemoryPersistence persistence = new MemoryPersistence();
            // tcp://broker.emqx.io:1883
            String url = String.format("tcp://%s:%s", brokerUrl, port);
            mqttClient = new MqttClient(url, clientId, persistence);

            // 设置回调
            mqttClient.setCallback(new MqttCallbackExtended() {

                @Override
                public void connectionLost(Throwable throwable) {

                }

                @Override
                public void messageArrived(String topic, MqttMessage mqttMessage) throws Exception {
                    String payload = new String(mqttMessage.getPayload(), StandardCharsets.UTF_8);
                    log.info("收到来自主题: {} 的消息: {}", topic, payload);

                    ByteBuf byteBuf = Unpooled.wrappedBuffer(mqttMessage.getPayload());
                    messageHandler.accept(SimpleMqttMessage.create(topic, byteBuf, mqttMessage.getQos()));
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {

                }

                @Override
                public void connectComplete(boolean b, String s) {

                }
            });

            // 配置连接选项
            connectOptions = new MqttConnectOptions();
            connectOptions.setCleanSession(cleanSession);
            connectOptions.setKeepAliveInterval(keepAliveInterval);
            connectOptions.setConnectionTimeout(connectionTimeout);

            if (username != null && !username.isEmpty()) {
                connectOptions.setUserName(username);
            }

            if (password != null && !password.isEmpty()) {
                connectOptions.setPassword(password.toCharArray());
            }

            // 设置自动重连
            connectOptions.setAutomaticReconnect(true);

            // 连接到broker
            mqttClient.connect(connectOptions);
            log.info("MQTT客户端连接成功: {}", getId());
        } catch (MqttException e) {
            log.error("MQTT客户端连接失败: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 发布消息
     *
     * @param topic    主题
     * @param payload  消息内容
     * @param qos      服务质量等级 (0, 1, 2)
     * @param retained 是否保留消息
     * @throws MqttException MQTT异常
     */
    public void publish(String topic, byte[] payload, int qos, boolean retained) throws MqttException {
        if (mqttClient == null || !mqttClient.isConnected()) {
            throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
        }

        MqttMessage message = new MqttMessage(payload);
        message.setQos(qos);
        message.setRetained(retained);

        mqttClient.publish(topic, message);
        log.debug("发布消息到主题: {}", topic);
    }

    /**
     * 订阅主题
     *
     * @param topic 主题
     * @param qos   服务质量等级 (0, 1, 2)
     * @throws MqttException MQTT异常
     */
    public void subscribe(String topic, int qos) throws MqttException {
        if (mqttClient == null || !mqttClient.isConnected()) {
            throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
        }

        mqttClient.subscribe(topic, qos);
        topicSubscriptions.put(topic, Integer.valueOf(qos));
        log.info("订阅主题: {}, QoS: {}", topic, Integer.valueOf(qos));
    }

    /**
     * 取消订阅主题
     *
     * @param topic 主题
     * @throws MqttException MQTT异常
     */
    public void unsubscribe(String topic) throws MqttException {
        if (mqttClient == null || !mqttClient.isConnected()) {
            throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
        }

        mqttClient.unsubscribe(topic);
        topicSubscriptions.remove(topic);
        messageHandler = null;
        log.info("取消订阅主题: {}", topic);
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                mqttClient.disconnect();
                log.info("MQTT客户端断开连接: {}", getId());
            } catch (MqttException e) {
                log.error("MQTT客户端断开连接失败: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * 重新订阅之前的主题
     */
    private void resubscribeTopics() {
        topicSubscriptions.forEach((topic, qos) -> {
            try {
                mqttClient.subscribe(topic, qos);
                log.info("重新订阅主题: {}, QoS: {}", topic, qos);
            } catch (MqttException e) {
                log.error("重新订阅主题失败: {}, 错误: {}", topic, e.getMessage(), e);
            }
        });
    }

    /**
     * 判断客户端是否已连接
     *
     * @return 连接状态
     */
    public boolean isConnected() {
        return mqttClient != null && mqttClient.isConnected();
    }

    @Override
    protected void doShutdown() {
        disconnect();
        log.info("关闭MQTT客户端: {}", getId());
    }
}
