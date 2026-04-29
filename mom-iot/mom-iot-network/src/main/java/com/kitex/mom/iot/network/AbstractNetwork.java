package com.kitex.mom.iot.network;


import com.kitex.mom.iot.core.network.NetworkType;

/**
 * 抽象网络组件，实现Network接口的基本功能
 */
public abstract class AbstractNetwork implements Network {

    private final String id;
    private final NetworkType type;
    private volatile boolean alive = false;

    /**
     * 构造函数
     *
     * @param id   网络组件ID
     * @param type 网络类型
     */
    public AbstractNetwork(String id, NetworkType type) {
        this.id = id;
        this.type = type;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public NetworkType getType() {
        return type;
    }

    @Override
    public boolean isAlive() {
        return alive;
    }


    @Override
    public void shutdown() {
        if (!alive) {
            return;
        }
        try {
            doShutdown();
        } finally {
            alive = false;
        }
    }

    /**
     * 关闭具体实现，子类需要实现此方法
     */
    protected abstract void doShutdown();

    @Override
    public boolean isAutoReload() {
        return true;
    }
} 