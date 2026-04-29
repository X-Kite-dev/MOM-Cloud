package com.kitex.mom.iot.network.tcp;


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
 * TCP网络提供商实现
 */
@Slf4j
@Component
public class TcpNetworkProvider implements NetworkProvider<TcpServerConfig> {

    @Override
    public TcpServerConfig createConfig(NetworkProperties properties) {
        TcpServerConfig config = new TcpServerConfig();
        config.setId(properties.getId());
        config.setName(properties.getName());
        config.setDescription(properties.getDescription());

        // 从properties中获取TCP服务器特定配置
        if (properties.getProperties() != null) {
            Object localAddress = properties.getProperties().get("publicAddress");
            Object localPort = properties.getProperties().get("publicPort");
            Object maxConnections = properties.getProperties().get("maxConnections");
            Object maxMessageSize = properties.getProperties().get("maxMessageSize");

            if (localAddress != null) {
                config.setLocalAddress(localAddress.toString());
            }
            if (localPort != null) {
                config.setLocalPort(Integer.parseInt(localPort.toString()));
            }
            if (maxConnections != null) {
                config.setMaxConnections(Integer.parseInt(maxConnections.toString()));
            }
            if (maxMessageSize != null) {
                config.setMaxMessageSize(Integer.parseInt(maxMessageSize.toString()));
            }
        }

        return config;
    }

    @Override
    public NetworkType getType() {
        return DefaultNetworkType.TCP_SERVER;
    }

    @Override
    public Network createNetwork(TcpServerConfig config) {
        // 创建TCP服务器实例
        TcpServer tcpServer = new TcpServer(config);

        // 初始化网络组件
        return initNetwork(tcpServer, config);
    }

    /**
     * 初始化TCP网络组件
     *
     * @param tcpServer TCP服务器实例
     * @param config    TCP服务器配置
     * @return 初始化后的网络组件
     */
    private Network initNetwork(TcpServer tcpServer, TcpServerConfig config) {
        try {
            log.info("开始初始化TCP服务器: id={}, address={}:{}",
                    config.getId(), config.getLocalAddress(), config.getLocalPort());

            // 创建boss和worker线程组
            EventLoopGroup bossGroup = new NioEventLoopGroup(1);
            EventLoopGroup workerGroup = new NioEventLoopGroup();

            // 设置线程组
            tcpServer.setEventLoopGroups(bossGroup, workerGroup);

            // 配置服务器
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(tcpServer.getTcpServerInitializer())
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            // 绑定端口并启动服务器
            String localAddress = config.getLocalAddress();
            int localPort = config.getLocalPort();

            Channel serverChannel;
            if (localAddress != null && !localAddress.isEmpty() && !localAddress.equals("0.0.0.0")) {
                serverChannel = bootstrap.bind(new InetSocketAddress(localAddress, localPort)).sync().channel();
            } else {
                serverChannel = bootstrap.bind(localPort).sync().channel();
            }

            // 设置服务器通道
            tcpServer.setServerChannel(serverChannel);

            log.info("TCP服务器启动成功，监听地址：{}:{}",
                    localAddress != null ? localAddress : "0.0.0.0", localPort);

            return tcpServer;
        } catch (Exception e) {
            log.error("初始化TCP服务器失败: {}", config.getId(), e);
            tcpServer.shutdown();
            throw new RuntimeException("初始化TCP服务器失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Network reload(Network network, TcpServerConfig config) {
        if (!(network instanceof TcpServer)) {
            throw new IllegalArgumentException("网络组件类型不匹配: " + network.getClass());
        }

        // 关闭原有网络组件
        TcpServer oldServer = (TcpServer) network;
        oldServer.shutdown();

        // 创建新的网络组件
        TcpServer newServer = new TcpServer(config);

        // 初始化新的网络组件
        return initNetwork(newServer, config);
    }

    @Override
    public ConfigMetadata getConfigMetadata() {
        return new DefaultConfigMetadata()
                .add("localAddress", "绑定地址", "TCP服务器绑定的本地地址，默认0.0.0.0")
                .add("localPort", "监听端口", "TCP服务器监听的端口号")
                .add("maxConnections", "最大连接数", "TCP服务器允许的最大客户端连接数")
                .add("maxMessageSize", "最大消息大小", "TCP服务器允许的最大消息大小(字节)");
    }

    @Override
    public boolean isReusable() {
        return false;
    }
}