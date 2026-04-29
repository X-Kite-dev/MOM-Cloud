package com.kitex.mom.iot.core.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网络类型工具类，用于管理所有的网络类型
 */
public class NetworkTypes {

    // 存储所有注册的网络类型
    private static final Map<String, NetworkType> all = new ConcurrentHashMap<>();

    /**
     * 获取所有支持的网络组件类型
     *
     * @return 网络类型列表
     */
    public static List<NetworkType> get() {
        return new ArrayList<>(all.values());
    }

    /**
     * 根据ID查找NetworkType
     *
     * @param id 类型ID
     * @return 可能包含NetworkType的Optional
     */
    public static Optional<NetworkType> lookup(String id) {
        return Optional.ofNullable(all.get(id));
    }

    /**
     * 注册网络类型
     *
     * @param type 网络类型
     * @return 被注册的网络类型
     */
    public static NetworkType register(NetworkType type) {
        all.put(type.getId(), type);
        return type;
    }

    /**
     * 取消注册网络类型
     *
     * @param id 网络类型ID
     * @return 被取消注册的网络类型
     */
    public static NetworkType unregister(String id) {
        return all.remove(id);
    }
} 