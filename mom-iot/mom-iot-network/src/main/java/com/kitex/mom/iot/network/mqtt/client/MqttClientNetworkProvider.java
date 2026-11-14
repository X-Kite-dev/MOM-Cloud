package com.kitex.mom.iot.network.mqtt.client;

import com.kitex.mom.iot.core.defaults.DefaultNetworkType;
import com.kitex.mom.iot.core.network.NetworkProperties;
import com.kitex.mom.iot.core.network.NetworkType;
import com.kitex.mom.iot.network.ConfigMetadata;
import com.kitex.mom.iot.network.DefaultConfigMetadata;
import com.kitex.mom.iot.network.Network;
import com.kitex.mom.iot.network.NetworkProvider;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.springframework.stereotype.Component;

/**
 * MQTT客户端网络提供商实现
 */
@Slf4j
@Component
public class MqttClientNetworkProvider implements NetworkProvider<MqttClientConfig> {

    @Override
    public MqttClientConfig createConfig(NetworkProperties properties) {
        MqttClientConfig config = new MqttClientConfig();
        config.setId(properties.getId());
        config.setName(properties.getName());
        config.setDescription(properties.getDescription());

        // 从properties中获取MQTT客户端特定配置
        if (properties.getProperties() != null) {
            Object brokerUrl = properties.getProperties().get("localAddress");
            Object clientId = properties.getProperties().get(properties.getId());
            Object username = properties.getProperties().get("username");
            Object password = properties.getProperties().get("password");
            Object keepAliveInterval = properties.getProperties().get("keepAliveInterval");
            Object cleanSession = properties.getProperties().get("cleanSession");
            Object connectionTimeout = properties.getProperties().get("connectionTimeout");
            Object port = properties.getProperties().get("publicPort");

            if (brokerUrl != null) {
                config.setBrokerUrl(brokerUrl.toString());
            }
            if (clientId != null) {
                config.setClientId(clientId.toString());
            }
            if (username != null) {
                config.setUsername(username.toString());
            }
            if (password != null) {
                config.setPassword(password.toString());
            }
            if (keepAliveInterval != null) {
                config.setKeepAliveInterval(Integer.parseInt(keepAliveInterval.toString()));
            }
            if (cleanSession != null) {
                config.setCleanSession(Boolean.parseBoolean(cleanSession.toString()));
            }
            if (connectionTimeout != null) {
                config.setConnectionTimeout(Integer.parseInt(connectionTimeout.toString()));
            }
            if (port != null) {
                config.setPort(String.valueOf(port));
            }
        }

        return config;
    }

    @Override
    public NetworkType getType() {
        return DefaultNetworkType.MQTT_CLIENT;
    }

    @Override
    public Network createNetwork(MqttClientConfig config) {
        // 创建MQTT客户端实例
        MqttBrokerClient mqttClient = new MqttBrokerClient(config.getId());


        // 初始化网络组件
        return initNetwork(mqttClient, config);
    }

    /**
     * 初始化MQTT客户端网络组件
     *
     * @param mqttClient MQTT客户端实例
     * @param config     MQTT客户端配置
     * @return 初始化后的网络组件
     */
    private Network initNetwork(MqttBrokerClient mqttClient, MqttClientConfig config) {
        try {
            log.info("开始初始化MQTT客户端: id={}, brokerUrl={}",
                    config.getId(), config.getBrokerUrl());

            // 根据用户的需求，这里我们不主动连接到服务器
            // 具体的连接操作将在MqttClientDeviceGateway.doStartup()方法中通过配置更新来执行
            // 但我们会创建好MqttClient对象，便于后续使用

            if (config.getBrokerUrl() == null || config.getBrokerUrl().isEmpty()) {
                throw new IllegalArgumentException("MQTT Broker URL未配置");
            }

            String clientId = config.getClientId();
            if (clientId == null || clientId.isEmpty()) {
                clientId = MqttClient.generateClientId();
            }
            // 设置连接参数
            mqttClient.setConnectionParams(
                    config.getBrokerUrl(),
                    config.getPort(),
                    clientId,
                    config.getUsername(),
                    config.getPassword()
            );

            // 设置连接选项
            mqttClient.setConnectionOptions(
                    config.getKeepAliveInterval(),
                    config.isCleanSession(),
                    config.getConnectionTimeout()
            );

            return mqttClient;
        } catch (Exception e) {
            log.error("初始化MQTT客户端失败: {}", config.getId(), e);
            mqttClient.shutdown();
            throw new RuntimeException("初始化MQTT客户端失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Network reload(Network network, MqttClientConfig config) {
        if (!(network instanceof MqttBrokerClient)) {
            throw new IllegalArgumentException("网络组件类型不匹配: " + network.getClass());
        }

        // 关闭原有网络组件
        MqttBrokerClient oldClient = (MqttBrokerClient) network;
        oldClient.shutdown();

        // 创建新的网络组件
        MqttBrokerClient newClient = new MqttBrokerClient(config.getId());

        // 初始化新的网络组件
        return initNetwork(newClient, config);
    }

    @Override
    public ConfigMetadata getConfigMetadata() {
        return new DefaultConfigMetadata()
                .add("brokerUrl", "服务器地址", "MQTT Broker的地址，格式为: tcp://host:port")
                .add("clientId", "客户端ID", "MQTT客户端ID，不指定时将自动生成")
                .add("username", "用户名", "连接MQTT Broker的用户名")
                .add("password", "密码", "连接MQTT Broker的密码")
                .add("keepAliveInterval", "心跳间隔", "MQTT心跳间隔时间(秒)，默认60秒")
                .add("cleanSession", "清除会话", "是否在连接时清除会话状态，默认true")
                .add("connectionTimeout", "连接超时", "连接超时时间(秒)，默认30秒");
    }

    @Override
    public boolean isReusable() {
        return false;
    }
}