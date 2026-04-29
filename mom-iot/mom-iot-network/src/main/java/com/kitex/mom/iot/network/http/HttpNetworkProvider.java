//package com.kitex.mom.iot.network.http;
//
//import com.kitex.mom.iot.gateway.network.NetworkProperties;
//import com.kitex.mom.iot.gateway.network.NetworkType;
//import com.kitex.mom.iot.network.ConfigMetadata;
//import com.kitex.mom.iot.network.DefaultConfigMetadata;
//import com.kitex.mom.iot.core.defaults.DefaultNetworkType;
//import com.kitex.mom.iot.network.Network;
//import com.kitex.mom.iot.network.NetworkProvider;
//import io.netty.bootstrap.ServerBootstrap;
//import io.netty.channel.Channel;
//import io.netty.channel.ChannelOption;
//import io.netty.channel.EventLoopGroup;
//import io.netty.channel.nio.NioEventLoopGroup;
//import io.netty.channel.socket.nio.NioServerSocketChannel;
//import io.netty.handler.logging.LogLevel;
//import io.netty.handler.logging.LoggingHandler;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.stereotype.Component;
//
//import java.nio.charset.StandardCharsets;
//
/// **
// * HTTP网络提供商实现
// */
//@Slf4j
//@Component
//public class HttpNetworkProvider implements NetworkProvider<HttpServerConfig> {
//
//    @Override
//    public HttpServerConfig createConfig(NetworkProperties properties) {
//        HttpServerConfig config = new HttpServerConfig();
//        config.setId(properties.getId());
//        config.setName(properties.getName());
//        config.setDescription(properties.getDescription());
//
//        // 从properties中获取HTTP服务器特定配置
//        if (properties.getProperties() != null) {
//            Object host = properties.getProperties().get("host");
//            Object port = properties.getProperties().get("port");
//            Object contextPath = properties.getProperties().get("contextPath");
//            Object maxConnections = properties.getProperties().get("maxConnections");
//            Object maxMessageSize = properties.getProperties().get("maxMessageSize");
//            Object sslEnabled = properties.getProperties().get("sslEnabled");
//            Object keystorePath = properties.getProperties().get("keystorePath");
//            Object keystorePassword = properties.getProperties().get("keystorePassword");
//
//            if (host != null) {
//                config.setHost(host.toString());
//            }
//            if (port != null) {
//                config.setPort(Integer.parseInt(port.toString()));
//            }
//            if (contextPath != null) {
//                config.setContextPath(contextPath.toString());
//            }
//            if (maxConnections != null) {
//                config.setMaxConnections(Integer.parseInt(maxConnections.toString()));
//            }
//            if (maxMessageSize != null) {
//                config.setMaxMessageSize(Integer.parseInt(maxMessageSize.toString()));
//            }
//            if (sslEnabled != null) {
//                config.setSslEnabled(Boolean.parseBoolean(sslEnabled.toString()));
//            }
//            if (keystorePath != null) {
//                config.setKeystorePath(keystorePath.toString());
//            }
//            if (keystorePassword != null) {
//                config.setKeystorePassword(keystorePassword.toString());
//            }
//        }
//
//        return config;
//    }
//
//    @Override
//    public NetworkType getType() {
//        return DefaultNetworkType.HTTP_SERVER;
//    }
//
//    @Override
//    public Network createNetwork(HttpServerConfig config) {
//        // 创建HTTP服务器实例
//        HttpServer httpServer = new HttpServer(
//                config.getId(),
//                config.getPort(),
//                config.getContextPath(),
//                config.getBossThreads(),
//                config.getWorkerThreads()
//        );
//
//        // 设置SSL配置
////        if (config.isSslEnabled()) {
////            httpServer.enableSsl(config.getKeystorePath(), config.getKeystorePassword());
////        }
//
//        // 初始化网络组件
//        return initNetwork(httpServer, config);
//    }
//
//    /**
//     * 初始化HTTP网络组件
//     *
//     * @param httpServer HTTP服务器实例
//     * @param config HTTP服务器配置
//     * @return 初始化后的网络组件
//     */
//    private Network initNetwork(HttpServer httpServer, HttpServerConfig config) {
//        try {
//            log.info("开始初始化HTTP服务器: id={}, address={}:{}, contextPath={}",
//                    config.getId(), config.getHost(), config.getPort(), config.getContextPath());
//
//            // 创建boss和worker线程组
//            EventLoopGroup bossGroup = new NioEventLoopGroup(config.getBossThreads());
//            EventLoopGroup workerGroup = new NioEventLoopGroup(config.getWorkerThreads());
//
//            // 设置线程组
//            httpServer.setEventLoopGroups(bossGroup, workerGroup);
//
//            // 配置服务器
//            ServerBootstrap bootstrap = new ServerBootstrap();
//            bootstrap.group(bossGroup, workerGroup)
//                    .channel(NioServerSocketChannel.class)
//                    .handler(new LoggingHandler(LogLevel.INFO))
//                    .childHandler(httpServer.getHttpServerInitializer())
//                    .option(ChannelOption.SO_BACKLOG, 128)
//                    .childOption(ChannelOption.SO_KEEPALIVE, true);
//
//            // 添加默认处理器
//            httpServer.addHandler("/", (request, response) -> {
//                String content = "<html><body>" +
//                        "<h1>MOM-IoT HTTP 服务器</h1>" +
//                        "<p>服务器运行正常。这是默认响应。</p>" +
//                        "</body></html>";
//
//                response.setContentType("text/html; charset=utf-8");
//                response.setStatusCode(200);
//                response.getOutputStream().write(content.getBytes(StandardCharsets.UTF_8));
//            });
//
//            // 绑定端口并启动服务器
//            Channel serverChannel = bootstrap.bind(config.getPort()).sync().channel();
//
//            // 设置服务器通道
//            httpServer.setServerChannel(serverChannel);
//
//            log.info("HTTP服务器启动成功，监听端口：{}", config.getPort());
//
//            return httpServer;
//        } catch (Exception e) {
//            log.error("初始化HTTP服务器失败: {}", config.getId(), e);
//            httpServer.shutdown();
//            throw new RuntimeException("初始化HTTP服务器失败: " + e.getMessage(), e);
//        }
//    }
//
//    @Override
//    public Network reload(Network network, HttpServerConfig config) {
//        if (!(network instanceof HttpServer)) {
//            throw new IllegalArgumentException("网络组件类型不匹配: " + network.getClass());
//        }
//
//        // 关闭原有网络组件
//        HttpServer oldServer = (HttpServer) network;
//        oldServer.shutdown();
//
//        // 创建新的网络组件
//        HttpServer newServer = new HttpServer(
//                config.getId(),
//                config.getPort(),
//                config.getContextPath(),
//                config.getBossThreads(),
//                config.getWorkerThreads()
//        );
//
//        // 设置SSL配置
////        if (config.isSslEnabled()) {
////            newServer.enableSsl(config.getKeystorePath(), config.getKeystorePassword());
////        }
//
//        // 初始化新的网络组件
//        return initNetwork(newServer, config);
//    }
//
//    @Override
//    public ConfigMetadata getConfigMetadata() {
//        return new DefaultConfigMetadata()
//                .add("host", "绑定地址", "HTTP服务器绑定的本地地址，默认0.0.0.0")
//                .add("port", "监听端口", "HTTP服务器监听的端口号，默认8080")
//                .add("contextPath", "上下文路径", "HTTP服务器的上下文路径，默认/")
//                .add("maxConnections", "最大连接数", "HTTP服务器允许的最大客户端连接数")
//                .add("maxMessageSize", "最大消息大小", "HTTP服务器允许的最大消息大小(字节)")
//                .add("sslEnabled", "启用SSL", "是否启用HTTPS")
//                .add("keystorePath", "证书路径", "SSL证书路径")
//                .add("keystorePassword", "证书密码", "SSL证书密码");
//    }
//
//    @Override
//    public boolean isReusable() {
//        return false;
//    }
//}