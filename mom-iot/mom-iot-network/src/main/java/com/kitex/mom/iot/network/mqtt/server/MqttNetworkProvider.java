package com.kitex.mom.iot.network.mqtt.server;

import com.kitex.mom.iot.core.defaults.DefaultNetworkType;
import com.kitex.mom.iot.core.network.NetworkProperties;
import com.kitex.mom.iot.core.network.NetworkType;
import com.kitex.mom.iot.network.ConfigMetadata;
import com.kitex.mom.iot.network.DefaultConfigMetadata;
import com.kitex.mom.iot.network.Network;
import com.kitex.mom.iot.network.NetworkProvider;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;

/**
 * MQTT网络提供商实现
 */
@Slf4j
@Component
public class MqttNetworkProvider implements NetworkProvider<MqttServerConfig> {

    @Override
    public MqttServerConfig createConfig(NetworkProperties properties) {
        MqttServerConfig config = new MqttServerConfig();
        config.setId(properties.getId());
        config.setName(properties.getName());
        config.setDescription(properties.getDescription());

        // 从properties中获取MQTT服务器特定配置
        if (properties.getProperties() != null) {
            Object host = properties.getProperties().get("publicAddress");
            Object port = properties.getProperties().get("publicPort");
            Object username = properties.getProperties().get("username");
            Object password = properties.getProperties().get("password");
            Object keepAlive = properties.getProperties().get("keepAlive");
            Object cleanSession = properties.getProperties().get("cleanSession");
            Object autoReconnect = properties.getProperties().get("autoReconnect");
            Object maxMessageSize = properties.getProperties().get("maxMessageSize");

            if (host != null) {
                config.setHost(host.toString());
            }
            if (port != null) {
                config.setPort(Integer.parseInt(port.toString()));
            }
            if (username != null) {
                config.setUsername(username.toString());
            }
            if (password != null) {
                config.setPassword(password.toString());
            }
            if (keepAlive != null) {
                config.setKeepAlive(Integer.parseInt(keepAlive.toString()));
            }
            if (cleanSession != null) {
                config.setCleanSession(Boolean.parseBoolean(cleanSession.toString()));
            }
            if (autoReconnect != null) {
                config.setAutoReconnect(Boolean.parseBoolean(autoReconnect.toString()));
            }
            if (maxMessageSize != null) {
                config.setMaxMessageSize(Integer.parseInt(maxMessageSize.toString()));
            }
        }

        return config;
    }

    @Override
    public NetworkType getType() {
        return DefaultNetworkType.MQTT_SERVER;
    }

    @Override
    public Network createNetwork(MqttServerConfig config) {
        // 创建MQTT服务器实例
        MqttServer mqttServer = new MqttServer(config);

        // 初始化网络组件
        return initNetwork(mqttServer, config);
    }

    /**
     * 初始化MQTT网络组件
     *
     * @param mqttServer MQTT服务器实例
     * @param config     MQTT服务器配置
     * @return 初始化后的网络组件
     */
    private Network initNetwork(MqttServer mqttServer, MqttServerConfig config) {
        try {
            log.info("开始初始化MQTT服务器: id={}, address={}:{}",
                    config.getId(), config.getHost(), config.getPort());

            // 创建boss和worker线程组
            EventLoopGroup bossGroup = new NioEventLoopGroup(1);
            EventLoopGroup workerGroup = new NioEventLoopGroup();

            // 设置线程组
            mqttServer.setEventLoopGroups(bossGroup, workerGroup);

            // 配置服务器
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(mqttServer.getMqttServerInitializer())
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            // 绑定端口并启动服务器
            String host = config.getHost();
            int port = config.getPort();

            Channel serverChannel;
            if (host != null && !host.isEmpty() && !host.equals("0.0.0.0")) {
                serverChannel = bootstrap.bind(new InetSocketAddress(host, port)).sync().channel();
            } else {
                serverChannel = bootstrap.bind(port).sync().channel();
            }

            // 设置服务器通道
            mqttServer.setServerChannel(serverChannel);

            log.info("MQTT服务器启动成功，监听地址：{}:{}",
                    host != null ? host : "0.0.0.0", port);

            return mqttServer;
        } catch (Exception e) {
            log.error("初始化MQTT服务器失败: {}", config.getId(), e);
            mqttServer.shutdown();
            throw new RuntimeException("初始化MQTT服务器失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Network reload(Network network, MqttServerConfig config) {
        if (!(network instanceof MqttServer)) {
            throw new IllegalArgumentException("网络组件类型不匹配: " + network.getClass());
        }

        // 关闭原有网络组件
        MqttServer oldServer = (MqttServer) network;
        oldServer.shutdown();

        // 创建新的网络组件
        MqttServer newServer = new MqttServer(config);

        // 初始化新的网络组件
        return initNetwork(newServer, config);
    }

    @Override
    public ConfigMetadata getConfigMetadata() {
        return new DefaultConfigMetadata()
                .add("host", "绑定地址", "MQTT服务器绑定的本地地址，默认0.0.0.0")
                .add("port", "监听端口", "MQTT服务器监听的端口号，默认1883")
                .add("username", "用户名", "MQTT服务器验证的用户名")
                .add("password", "密码", "MQTT服务器验证的密码")
                .add("keepAlive", "心跳间隔", "MQTT心跳间隔时间(秒)")
                .add("cleanSession", "清除会话", "是否在连接时清除会话状态")
                .add("autoReconnect", "自动重连", "是否在连接断开时自动重连")
                .add("maxMessageSize", "最大消息大小", "MQTT服务器允许的最大消息大小(字节)");
    }

    @Override
    public boolean isReusable() {
        return false;
    }
}