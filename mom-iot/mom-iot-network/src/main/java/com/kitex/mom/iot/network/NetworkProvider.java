package com.kitex.mom.iot.network;


import com.kitex.mom.iot.core.network.NetworkProperties;
import com.kitex.mom.iot.core.network.NetworkType;

/**
 * 网络组件提供商接口，用于创建和管理网络组件
 *
 * @param <P> 网络配置类型
 */
public interface NetworkProvider<P> {

    /**
     * 获取网络类型
     *
     * @return 网络类型
     */
    NetworkType getType();

    /**
     * 创建网络组件
     *
     * @param properties 配置属性
     * @return 网络组件
     */
    Network createNetwork(P properties);

    /**
     * 重新加载网络组件
     *
     * @param network    原网络组件
     * @param properties 新配置属性
     * @return 新网络组件
     */
    Network reload(Network network, P properties);

    /**
     * 获取配置元数据
     *
     * @return 配置元数据
     */
    ConfigMetadata getConfigMetadata();

    /**
     * 创建网络配置
     *
     * @param properties 网络属性
     * @return 网络配置
     */
    P createConfig(NetworkProperties properties);

    /**
     * 是否可复用
     *
     * @return 是否可复用
     */
    default boolean isReusable() {
        return false;
    }
} 