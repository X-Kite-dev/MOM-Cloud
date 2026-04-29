package com.kitex.mom.iot.gateway;

import java.util.function.BiConsumer;

/**
 * 设备网关接口，用于统一管理设备连接、状态及消息收发
 */
public interface DeviceGateway {

    /**
     * 获取网关ID
     *
     * @return 网关ID
     */
    String getId();

    /**
     * 获取消息监听器
     *
     * @return 消息监听器
     */
    MessageListener getMessageListener();

    /**
     * 设置消息监听器
     *
     * @param listener 消息监听器
     */
    void setMessageListener(MessageListener listener);

    /**
     * 启动网关
     */
    void startup();

    /**
     * 暂停网关
     */
    void pause();

    /**
     * 关闭网关
     */
    void shutdown();

    /**
     * 检查网关是否存活
     *
     * @return 是否存活
     */
    default boolean isAlive() {
        return true;
    }

    /**
     * 检查网关是否已启动
     *
     * @return 是否已启动
     */
    default boolean isStarted() {
        return getState() == GatewayState.started;
    }

    /**
     * 获取网关状态
     *
     * @return 网关状态
     */
    default GatewayState getState() {
        return GatewayState.started;
    }

    /**
     * 注册状态变更监听器
     *
     * @param listener 状态变更监听器
     */
    default void doOnStateChange(BiConsumer<GatewayState, GatewayState> listener) {
    }

    /**
     * 注册关闭时的处理逻辑
     *
     * @param runnable 处理逻辑
     */
    default void doOnShutdown(Runnable runnable) {
        doOnStateChange((before, after) -> {
            if (after == GatewayState.shutdown) {
                runnable.run();
            }
        });
    }

    /**
     * 消息监听器接口
     */
    interface MessageListener {
        /**
         * 处理接收到的消息
         *
         * @param message 消息
         */
        void onMessage(Message message);
    }
} 