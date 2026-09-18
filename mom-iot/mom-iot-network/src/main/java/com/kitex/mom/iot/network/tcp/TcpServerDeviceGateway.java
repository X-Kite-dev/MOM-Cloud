package com.kitex.mom.iot.network.tcp;

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

/**
 * 基于TCP服务器的设备网关实现
 */
@Slf4j
public class TcpServerDeviceGateway extends AbstractDeviceGateway {

    final TcpServer tcpServer;
    private final DeviceRegistry registry;
    private final HandleMessageHelpr handleMessageHelpr;
    private DeviceMessageCodec codecMono;
    private ProtocolSupport protocol;

    /**
     * 构造函数
     *
     * @param id 网关ID
     */
    public TcpServerDeviceGateway(String id, TcpServer tcpServer, ProtocolSupport protocolSupport, DeviceRegistry registry, HandleMessageHelpr handleMessageHelpr) {
        super(id);
        this.tcpServer = tcpServer;
        setProtocol(protocolSupport);
        this.registry = registry;
        this.handleMessageHelpr = handleMessageHelpr;
    }

    public void setProtocol(ProtocolSupport protocol) {
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.codecMono = protocol.getCodec(TransportType.TCP);
    }

    @Override
    protected void doShutdown() {
        log.info("TCP服务器网关[{}]关闭", getId());
        // 网络组件由NetworkManager管理，这里不主动关闭
    }

    @Override
    protected void doStartup() {
        if (tcpServer == null) {
            throw new IllegalStateException("TCP服务器未配置");
        }

        tcpServer.setMessageListener(msg -> {
            log.info("收到消息：{}", msg);
            DeviceMessage deviceMessage = codecMono.decode(FromDeviceMessageContext.of(
                    msg,
                    registry
            ));
            // 设备中的信息
            log.info("获取设备转码之后的信息 -》 {}", deviceMessage.getProperties());
            // 发送消息到
            handleMessageHelpr.handleMessage(getId(), deviceMessage);
        });

        log.info("TCP服务器网关[{}]启动，连接到TCP服务: {}:{}",
                getId(), tcpServer.getLocalAddress(), tcpServer.getPort());
    }

    /**
     * 获取TCP服务器实例
     *
     * @return TCP服务器实例
     */
    public TcpServer getTcpServer() {
        return tcpServer;
    }
}
