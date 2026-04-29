package com.kitex.mom.iot.network;


import com.kitex.mom.iot.core.network.NetworkType;

/**
 * 网络接口，所有网络组件的根接口
 */
public interface Network {

    /**
     * 获取唯一标识ID
     *
     * @return 网络组件ID
     */
    String getId();

    /**
     * 获取网络类型
     *
     * @return 网络类型
     */
    NetworkType getType();

    /**
     * 关闭网络组件
     */
    void shutdown();

    /**
     * 判断网络组件是否存活
     *
     * @return 是否存活
     */
    boolean isAlive();

    /**
     * 是否自动重新加载
     *
     * @return 是否自动重新加载
     */
    boolean isAutoReload();
} 