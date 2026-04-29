package com.kitex.mom.iot.gateway.monitor;

/**
 * 设备网关监控接口，用于监控网关的运行状态
 */
public interface DeviceGatewayMonitor {

    /**
     * 创建一个空的监控实现
     *
     * @param gatewayId 网关ID
     * @return 监控实现
     */
    static DeviceGatewayMonitor empty(String gatewayId) {
        return new DeviceGatewayMonitor() {
            @Override
            public String getGatewayId() {
                return gatewayId;
            }

            @Override
            public void deviceConnected() {
            }

            @Override
            public void deviceDisconnected() {
            }

            @Override
            public void receiveMessage(int size) {
            }

            @Override
            public void sentMessage(int size) {
            }

            @Override
            public void handleError(Throwable error) {
            }

            @Override
            public void handleComplete() {
            }
        };
    }

    /**
     * 获取监控的网关ID
     *
     * @return 网关ID
     */
    String getGatewayId();

    /**
     * 设备连接
     */
    void deviceConnected();

    /**
     * 设备断开连接
     */
    void deviceDisconnected();

    /**
     * 接收到消息
     *
     * @param size 消息大小
     */
    void receiveMessage(int size);

    /**
     * 发送消息
     *
     * @param size 消息大小
     */
    void sentMessage(int size);

    /**
     * 处理消息错误
     *
     * @param error 错误信息
     */
    void handleError(Throwable error);

    /**
     * 处理消息完成
     */
    void handleComplete();
} 