package com.kitex.mom.iot.network;


import com.kitex.mom.iot.core.network.NetworkType;

import java.util.List;
import java.util.Optional;

/**
 * 网络管理器接口，用于统一管理网络组件
 */
public interface NetworkManager {

    /**
     * 获取指定类型和ID的网络组件
     *
     * @param type 网络类型
     * @param id   网络ID
     * @param <T>  网络组件类型
     * @return 网络组件
     */
    <T extends Network> T getNetwork(NetworkType type, String id);

    /**
     * 获取所有网络组件
     *
     * @return 网络组件列表
     */
    List<Network> getNetworks();

    /**
     * 获取所有网络组件提供商
     *
     * @return 网络组件提供商列表
     */
    List<NetworkProvider<?>> getProviders();

    /**
     * 根据类型获取提供商
     *
     * @param type 网络类型
     * @return 网络组件提供商
     */
    Optional<NetworkProvider<?>> getProvider(String type);

    /**
     * 重新加载网络组件
     *
     * @param type 网络类型
     * @param id   网络ID
     */
    void reload(NetworkType type, String id);

    /**
     * 停止网络组件
     *
     * @param type 网络类型
     * @param id   网络ID
     */
    void shutdown(NetworkType type, String id);

    /**
     * 销毁网络组件
     *
     * @param type 网络类型
     * @param id   网络ID
     */
    void destroy(NetworkType type, String id);
} 