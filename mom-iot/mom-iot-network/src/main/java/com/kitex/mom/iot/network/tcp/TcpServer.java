package com.kitex.mom.iot.network.tcp;

import com.kitex.mom.iot.core.defaults.DefaultNetworkType;
import com.kitex.mom.iot.core.message.codec.TcpMessage;
import com.kitex.mom.iot.network.AbstractNetwork;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * TCP服务器网络组件（基于Netty实现）
 */
@Slf4j
public class TcpServer extends AbstractNetwork {

    /**
     * -- GETTER --
     * 获取TCP服务器配置
     *
     * @return TCP服务器配置
     */
    @Getter
    private final TcpServerConfig config;
    private final Map<String, Channel> clients = new ConcurrentHashMap<>();
    private final AtomicInteger connectionCounter = new AtomicInteger(0);
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    @Setter
    private Consumer<TcpMessage> messageListener;
    /**
     * -- SETTER --
     * 设置服务器通道
     *
     * @param serverChannel 服务器通道
     */
    @Setter
    private Channel serverChannel;

    /**
     * 构造函数
     *
     * @param config TCP服务器配置
     */
    public TcpServer(TcpServerConfig config) {
        super(config.getId(), DefaultNetworkType.TCP_SERVER);
        this.config = config;
    }


    @Override
    protected void doShutdown() {
        // 关闭所有客户端连接
        for (Channel client : clients.values()) {
            client.close();
        }
        clients.clear();

        // 关闭服务器Channel
        if (serverChannel != null) {
            serverChannel.close();
            serverChannel = null;
        }

        // 关闭线程组
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }

        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }

        log.info("TCP服务器已关闭");
    }

    /**
     * 发送数据到客户端
     *
     * @param clientId 客户端ID
     * @param data     数据
     * @return 是否发送成功
     */
    public boolean sendToClient(String clientId, byte[] data) {
        Channel channel = clients.get(clientId);
        if (channel != null && channel.isActive()) {
            ByteBuf buf = Unpooled.wrappedBuffer(data);
            channel.writeAndFlush(buf);
            return true;
        }
        return false;
    }

    /**
     * 获取当前连接数
     *
     * @return 当前连接数
     */
    public int getCurrentConnections() {
        return connectionCounter.get();
    }

    /**
     * 获取本地地址
     *
     * @return 本地地址
     */
    public String getLocalAddress() {
        return config.getLocalAddress();
    }

    /**
     * 获取本地端口
     *
     * @return 本地端口
     */
    public int getPort() {
        return config.getLocalPort();
    }

    /**
     * 获取最大连接数
     *
     * @return 最大连接数
     */
    public int getMaxConnections() {
        return config.getMaxConnections();
    }

    /**
     * 设置线程组
     *
     * @param bossGroup   Boss线程组
     * @param workerGroup Worker线程组
     */
    public void setEventLoopGroups(EventLoopGroup bossGroup, EventLoopGroup workerGroup) {
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
    }

    /**
     * 获取TCP服务器初始化器，用于配置子通道处理器
     *
     * @return TCP服务器初始化器
     */
    public TcpServerInitializer getTcpServerInitializer() {
        return new TcpServerInitializer();
    }

    /**
     * TCP服务器初始化器
     */
    private class TcpServerInitializer extends ChannelInitializer<SocketChannel> {
        @Override
        protected void initChannel(SocketChannel ch) {
            ChannelPipeline pipeline = ch.pipeline();

            // 日志处理器
            pipeline.addLast(new LoggingHandler(LogLevel.DEBUG));

            // 编解码器
            pipeline.addLast(new ByteArrayDecoder());
            pipeline.addLast(new ByteArrayEncoder());

            // 业务处理器
            pipeline.addLast(new TcpServerHandler());
        }
    }

    /**
     * TCP服务器处理器
     */
    private class TcpServerHandler extends SimpleChannelInboundHandler<byte[]> {

        private String clientId;

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            // 检查是否达到最大连接数
            if (connectionCounter.incrementAndGet() > config.getMaxConnections()) {
                log.warn("TCP服务器连接数达到上限：{}", config.getMaxConnections());
                connectionCounter.decrementAndGet();
                ctx.close();
                return;
            }

            // 生成客户端ID
            Channel channel = ctx.channel();
            InetSocketAddress remoteAddress = (InetSocketAddress) channel.remoteAddress();
            clientId = remoteAddress.getHostString() + ":" + remoteAddress.getPort();

            // 保存客户端连接
            clients.put(clientId, channel);

            log.info("客户端连接：{}", clientId);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            // 移除客户端连接
            if (clientId != null) {
                clients.remove(clientId);
                connectionCounter.decrementAndGet();
                log.info("客户端断开连接：{}", clientId);
            }
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, byte[] msg) {
            try {
                // 处理接收到的数据

                String payloadString = new String(msg, StandardCharsets.UTF_8);
                log.info("收到客户端[{}]数据：{} 字节: 内容 {}", clientId, msg.length, payloadString);


                // 回显数据（示例）
                ByteBuf buf = Unpooled.wrappedBuffer(msg);
                messageListener.accept(new TcpMessage(buf));
                ctx.writeAndFlush(buf);
            } catch (Exception e) {
                log.error("处理客户端数据异常", e);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("TCP客户端异常：{}", cause.getMessage());
            ctx.close();
        }
    }
}