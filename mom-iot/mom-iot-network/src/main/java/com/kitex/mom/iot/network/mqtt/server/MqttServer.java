package com.kitex.mom.iot.network.mqtt.server;

import com.kitex.mom.iot.core.defaults.DefaultNetworkType;
import com.kitex.mom.iot.core.message.codec.SimpleMqttMessage;
import com.kitex.mom.iot.network.AbstractNetwork;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.mqtt.*;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * MQTT服务器实现，基于Netty
 */
@Slf4j
public class MqttServer extends AbstractNetwork {

    /**
     * -- GETTER --
     * 获取配置
     *
     * @return MQTT服务器配置
     */
    @Getter
    private final MqttServerConfig config;
    private final Map<String, MqttClient> clients = new ConcurrentHashMap<>();
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    @Setter
    private Consumer<SimpleMqttMessage> messageListener;

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
     * @param config MQTT服务器配置
     */
    public MqttServer(MqttServerConfig config) {
        super(config.getId(), DefaultNetworkType.MQTT_SERVER);
        this.config = config;
    }


    @Override
    protected void doShutdown() {
        // 关闭所有客户端连接
        for (MqttClient client : clients.values()) {
            client.disconnect();
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

        log.info("MQTT服务器已关闭");
    }

    /**
     * 发布消息
     *
     * @param topic   主题
     * @param payload 消息内容
     * @param qos     服务质量
     * @param retain  是否保留
     */
    public void publish(String topic, byte[] payload, int qos, boolean retain) {
        for (MqttClient client : clients.values()) {
            if (client.isSubscribed(topic)) {
                client.sendPublishMessage(topic, payload, qos, retain);
            }
        }
    }

    /**
     * 获取当前连接数
     *
     * @return 当前连接数
     */
    public int getCurrentConnections() {
        return clients.size();
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
     * 获取MQTT服务器初始化器，用于配置子通道处理器
     *
     * @return MQTT服务器初始化器
     */
    public MqttServerInitializer getMqttServerInitializer() {
        return new MqttServerInitializer();
    }

    /**
     * MQTT客户端信息
     */
    private static class MqttClient {
        private final String clientId;
        private final Channel channel;
        private final Map<String, Integer> subscriptions = new ConcurrentHashMap<>();

        public MqttClient(String clientId, Channel channel) {
            this.clientId = clientId;
            this.channel = channel;
        }

        public String getClientId() {
            return clientId;
        }

        public void addSubscription(String topic, int qos) {
            subscriptions.put(topic, qos);
        }

        public void removeSubscription(String topic) {
            subscriptions.remove(topic);
        }

        public boolean isSubscribed(String topic) {
            return subscriptions.containsKey(topic) || isWildcardMatch(topic);
        }

        private boolean isWildcardMatch(String topic) {
            // 简单实现，只支持`#`和`+`通配符
            for (String subscription : subscriptions.keySet()) {
                if (topicMatches(subscription, topic)) {
                    return true;
                }
            }
            return false;
        }

        private boolean topicMatches(String subscription, String topic) {
            String[] subParts = subscription.split("/");
            String[] topicParts = topic.split("/");

            // 如果订阅是以 # 结尾，只需要匹配前面的部分
            if (subscription.endsWith("/#")) {
                // 去掉最后的 # 部分
                int len = subParts.length - 1;
                if (topicParts.length < len) {
                    return false;
                }

                for (int i = 0; i < len - 1; i++) {
                    if (!subParts[i].equals("+") && !subParts[i].equals(topicParts[i])) {
                        return false;
                    }
                }
                return true;
            }

            // 否则需要完全匹配
            if (subParts.length != topicParts.length) {
                return false;
            }

            for (int i = 0; i < subParts.length; i++) {
                if (!subParts[i].equals("+") && !subParts[i].equals(topicParts[i])) {
                    return false;
                }
            }
            return true;
        }

        public void sendPublishMessage(String topic, byte[] payload, int qos, boolean retain) {
            if (!channel.isActive()) {
                return;
            }

            Integer subscriptionQos = subscriptions.get(topic);
            if (subscriptionQos == null) {
                for (Map.Entry<String, Integer> entry : subscriptions.entrySet()) {
                    if (topicMatches(entry.getKey(), topic)) {
                        subscriptionQos = entry.getValue();
                        break;
                    }
                }

                if (subscriptionQos == null) {
                    return; // 未订阅此主题
                }
            }

            // 使用客户端订阅的QoS和发布消息的QoS中的较小值
            int effectiveQos = Math.min(qos, subscriptionQos);

            MqttPublishMessage publishMessage = MqttMessageFactory.newPublishMessage(MqttQoS.valueOf(effectiveQos), retain, 0, topic, payload);

            channel.writeAndFlush(publishMessage);
        }

        public void disconnect() {
            if (channel.isActive()) {
                channel.close();
            }
        }
    }

    /**
     * MQTT消息工厂
     */
    private static class MqttMessageFactory {
        public static MqttConnAckMessage newConnAckMessage(MqttConnectReturnCode returnCode, boolean sessionPresent) {
            MqttFixedHeader fixedHeader = new MqttFixedHeader(MqttMessageType.CONNACK, false, MqttQoS.AT_MOST_ONCE, false, 0);
            MqttConnAckVariableHeader variableHeader = new MqttConnAckVariableHeader(returnCode, sessionPresent);
            return new MqttConnAckMessage(fixedHeader, variableHeader);
        }

        public static MqttPubAckMessage newPubAckMessage(int packetId) {
            MqttFixedHeader fixedHeader = new MqttFixedHeader(MqttMessageType.PUBACK, false, MqttQoS.AT_MOST_ONCE, false, 0);
            MqttMessageIdVariableHeader variableHeader = MqttMessageIdVariableHeader.from(packetId);
            return new MqttPubAckMessage(fixedHeader, variableHeader);
        }


        public static MqttSubAckMessage newSubAckMessage(int packetId, List<Integer> grantedQoSLevels) {
            MqttFixedHeader fixedHeader = new MqttFixedHeader(MqttMessageType.SUBACK, false, MqttQoS.AT_MOST_ONCE, false, 0);
            MqttMessageIdVariableHeader variableHeader = MqttMessageIdVariableHeader.from(packetId);
            MqttSubAckPayload payload = new MqttSubAckPayload(grantedQoSLevels);
            return new MqttSubAckMessage(fixedHeader, variableHeader, payload);
        }

        public static MqttUnsubAckMessage newUnsubAckMessage(int packetId) {
            MqttFixedHeader fixedHeader = new MqttFixedHeader(MqttMessageType.UNSUBACK, false, MqttQoS.AT_MOST_ONCE, false, 0);
            MqttMessageIdVariableHeader variableHeader = MqttMessageIdVariableHeader.from(packetId);
            return new MqttUnsubAckMessage(fixedHeader, variableHeader);
        }

        public static MqttMessage newPingRespMessage() {
            MqttFixedHeader fixedHeader = new MqttFixedHeader(MqttMessageType.PINGRESP, false, MqttQoS.AT_MOST_ONCE, false, 0);
            return new MqttMessage(fixedHeader);
        }

        public static MqttPublishMessage newPublishMessage(MqttQoS qos, boolean retain, int packetId, String topicName, byte[] payload) {
            MqttFixedHeader fixedHeader = new MqttFixedHeader(MqttMessageType.PUBLISH, false, qos, retain, 0);
            MqttPublishVariableHeader variableHeader = new MqttPublishVariableHeader(topicName, packetId);
            return new MqttPublishMessage(fixedHeader, variableHeader, io.netty.buffer.Unpooled.wrappedBuffer(payload));
        }
    }

    /**
     * MQTT服务器初始化器
     */
    private class MqttServerInitializer extends ChannelInitializer<SocketChannel> {
        @Override
        protected void initChannel(SocketChannel ch) {
            ChannelPipeline pipeline = ch.pipeline();

            // 空闲检测处理器
            pipeline.addLast("idle", new IdleStateHandler(0, 0, config.getKeepAlive(), TimeUnit.SECONDS));

            // MQTT编解码器
            pipeline.addLast("encoder", MqttEncoder.INSTANCE);
            pipeline.addLast("decoder", new MqttDecoder(config.getMaxMessageSize()));

            // 业务处理器
            pipeline.addLast("handler", new MqttServerHandler());
        }
    }

    /**
     * MQTT服务器处理器
     */
    private class MqttServerHandler extends ChannelInboundHandlerAdapter {
        private MqttClient client;

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            log.debug("MQTT客户端连接：{}", ctx.channel().remoteAddress());
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (client != null) {
                log.info("MQTT客户端断开连接：{}", client.getClientId());
                clients.remove(client.getClientId());
                client = null;
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof MqttMessage message) {

                try {
                    switch (message.fixedHeader().messageType()) {
                        case CONNECT:
                            handleConnect(ctx, (MqttConnectMessage) message);
                            break;
                        case PUBLISH:
                            handlePublish(ctx, (MqttPublishMessage) message);
                            break;
                        case SUBSCRIBE:
                            handleSubscribe(ctx, (MqttSubscribeMessage) message);
                            break;
                        case UNSUBSCRIBE:
                            handleUnsubscribe(ctx, (MqttUnsubscribeMessage) message);
                            break;
                        case PINGREQ:
                            handlePingReq(ctx);
                            break;
                        case DISCONNECT:
                            handleDisconnect(ctx);
                            break;
                        default:
                            log.warn("未处理的MQTT消息类型：{}", message.fixedHeader().messageType());
                    }
                } catch (Exception e) {
                    log.error("处理MQTT消息异常", e);
                    ctx.close();
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("MQTT客户端异常：{}", cause.getMessage());
            ctx.close();
        }

        /**
         * 处理CONNECT消息
         */
        private void handleConnect(ChannelHandlerContext ctx, MqttConnectMessage message) {
            String clientId = message.payload().clientIdentifier();
            log.info("MQTT客户端连接：{}", clientId);

            // 检查认证
            boolean authSuccess = true;
            if (config.getUsername() != null && config.getPassword() != null) {
                String username = message.payload().userName();
                String password = message.payload().passwordInBytes() != null ? new String(message.payload().passwordInBytes()) : null;

                if (!config.getUsername().equals(username) || !config.getPassword().equals(password)) {
                    authSuccess = false;
                }
            }

            // 发送连接响应
            MqttConnAckMessage connAckMessage;
            if (authSuccess) {
                connAckMessage = MqttMessageFactory.newConnAckMessage(MqttConnectReturnCode.CONNECTION_ACCEPTED, message.variableHeader().isCleanSession());

                // 创建客户端对象
                client = new MqttClient(clientId, ctx.channel());
                clients.put(clientId, client);
            } else {
                connAckMessage = MqttMessageFactory.newConnAckMessage(MqttConnectReturnCode.CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD, false);
            }

            ctx.writeAndFlush(connAckMessage);

            // 如果认证失败，关闭连接
            if (!authSuccess) {
                ctx.close();
            }
        }

        /**
         * 处理PUBLISH消息
         */
        private void handlePublish(ChannelHandlerContext ctx, MqttPublishMessage message) {
            if (client == null) {
                log.warn("收到未认证客户端的PUBLISH消息");
                return;
            }

            String topic = message.variableHeader().topicName();
            int qos = message.fixedHeader().qosLevel().value();
            int packetId = message.variableHeader().packetId();
            boolean retain = message.fixedHeader().isRetain();

            byte[] payload = new byte[message.payload().readableBytes()];
            message.payload().getBytes(message.payload().readerIndex(), payload);

            log.debug("收到PUBLISH消息：topic={}, qos={}, packetId={}, retain={}, payload={}bytes", topic, qos, packetId, retain, payload.length);

            String payloadString = new String(payload, StandardCharsets.UTF_8);
            messageListener.accept(SimpleMqttMessage.create(topic, message.payload(), qos));
            // 发布消息到其他客户端
            publish(topic, payload, qos, retain);

            // 根据QoS发送确认
            if (qos == 1) {
                MqttPubAckMessage pubAckMessage = MqttMessageFactory.newPubAckMessage(packetId);
                ctx.writeAndFlush(pubAckMessage);
            }
        }

        /**
         * 处理SUBSCRIBE消息
         */
        private void handleSubscribe(ChannelHandlerContext ctx, MqttSubscribeMessage message) {
            if (client == null) {
                log.warn("收到未认证客户端的SUBSCRIBE消息");
                return;
            }

            int packetId = message.variableHeader().messageId();
            List<MqttTopicSubscription> subscriptions = message.payload().topicSubscriptions();
            List<Integer> grantedQoSLevels = new ArrayList<>(subscriptions.size());

            for (MqttTopicSubscription subscription : subscriptions) {
                String topic = subscription.topicName();
                int qos = subscription.qualityOfService().value();

                // 添加订阅
                client.addSubscription(topic, qos);
                grantedQoSLevels.add(qos);

                log.debug("客户端[{}]订阅主题：{}, QoS={}", client.getClientId(), topic, qos);
            }

            // 发送确认
            MqttSubAckMessage subAckMessage = MqttMessageFactory.newSubAckMessage(packetId, grantedQoSLevels);
            ctx.writeAndFlush(subAckMessage);
        }

        /**
         * 处理UNSUBSCRIBE消息
         */
        private void handleUnsubscribe(ChannelHandlerContext ctx, MqttUnsubscribeMessage message) {
            if (client == null) {
                log.warn("收到未认证客户端的UNSUBSCRIBE消息");
                return;
            }

            int packetId = message.variableHeader().messageId();
            List<String> topics = message.payload().topics();

            for (String topic : topics) {
                client.removeSubscription(topic);
                log.debug("客户端[{}]取消订阅主题：{}", client.getClientId(), topic);
            }

            // 发送确认
            MqttUnsubAckMessage unsubAckMessage = MqttMessageFactory.newUnsubAckMessage(packetId);
            ctx.writeAndFlush(unsubAckMessage);
        }

        /**
         * 处理PINGREQ消息
         */
        private void handlePingReq(ChannelHandlerContext ctx) {
            MqttMessage pingRespMessage = MqttMessageFactory.newPingRespMessage();
            ctx.writeAndFlush(pingRespMessage);
        }

        /**
         * 处理DISCONNECT消息
         */
        private void handleDisconnect(ChannelHandlerContext ctx) {
            if (client != null) {
                log.info("客户端[{}]请求断开连接", client.getClientId());
                clients.remove(client.getClientId());
                client = null;
            }

            ctx.close();
        }
    }

}
