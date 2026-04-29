package com.kitex.mom.iot.gateway;

import com.kitex.mom.iot.gateway.monitor.DeviceGatewayMonitor;
import com.kitex.mom.iot.gateway.monitor.GatewayMonitors;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * 抽象设备网关，实现DeviceGateway接口的基本功能
 */
public abstract class AbstractDeviceGateway implements DeviceGateway {

    /**
     * 网关监控器
     */
    protected final DeviceGatewayMonitor monitor;
    /**
     * 网关ID
     */
    private final String id;
    /**
     * 状态监听器列表
     */
    private final List<BiConsumer<GatewayState, GatewayState>> stateListeners = new CopyOnWriteArrayList<>();
    /**
     * 网关状态
     */
    private volatile GatewayState state = GatewayState.shutdown;
    /**
     * 消息监听器
     */
    private MessageListener messageListener;

    /**
     * 构造函数
     *
     * @param id 网关ID
     */
    public AbstractDeviceGateway(String id) {
        this.id = id;
        this.monitor = GatewayMonitors.getDeviceGatewayMonitor(id);
    }

    @Override
    public final String getId() {
        return id;
    }

    @Override
    public MessageListener getMessageListener() {
        return messageListener;
    }

    @Override
    public void setMessageListener(MessageListener listener) {
        this.messageListener = listener;
    }

    @Override
    public final synchronized void startup() {
        if (state == GatewayState.paused) {
            changeState(GatewayState.started);
            return;
        }
        if (state == GatewayState.started || state == GatewayState.starting) {
            return;
        }
        changeState(GatewayState.starting);
        try {
            doStartup();
            changeState(GatewayState.started);
        } catch (Exception e) {
            changeState(GatewayState.shutdown);
            throw e;
        }
    }

    @Override
    public final void pause() {
        changeState(GatewayState.paused);
    }

    @Override
    public final void shutdown() {
        GatewayState oldState = state;
        if (oldState == GatewayState.shutdown) {
            return;
        }
        changeState(GatewayState.shutdown);
        doShutdown();
    }

    /**
     * 子类需要实现的关闭逻辑
     */
    protected abstract void doShutdown();

    /**
     * 子类需要实现的启动逻辑
     */
    protected abstract void doStartup();

    /**
     * 状态变更处理
     *
     * @param target 目标状态
     */
    protected synchronized final void changeState(GatewayState target) {
        GatewayState old = this.state;
        this.state = target;
        if (target == old) {
            return;
        }
        for (BiConsumer<GatewayState, GatewayState> consumer : stateListeners) {
            try {
                consumer.accept(old, this.state);
            } catch (Throwable error) {
                // 记录日志
                System.err.println("处理网关状态变更监听器时发生错误: " + error.getMessage());
            }
        }
    }

    /**
     * 处理接收到的消息
     *
     * @param message 消息
     */
    protected void handleMessage(Message message) {
        if (messageListener != null) {
            try {
                messageListener.onMessage(message);
            } catch (Exception e) {
                // 记录日志
                System.err.println("处理消息时发生错误: " + e.getMessage());
                monitor.handleError(e);
            }
        }
    }

    @Override
    public final GatewayState getState() {
        return state;
    }

    @Override
    public final void doOnStateChange(BiConsumer<GatewayState, GatewayState> listener) {
        stateListeners.add(listener);
    }

    @Override
    public final boolean isAlive() {
        return state == GatewayState.started || state == GatewayState.starting;
    }
} 