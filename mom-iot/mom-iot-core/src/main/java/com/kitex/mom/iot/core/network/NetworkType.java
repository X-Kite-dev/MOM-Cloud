package com.kitex.mom.iot.core.network;

import java.util.List;
import java.util.Optional;

/**
 * 网络类型接口，定义了网络组件类型的分类
 */
public interface NetworkType {

    /**
     * 根据ID创建NetworkType
     *
     * @param id 类型ID
     * @return NetworkType实例
     */
    static NetworkType of(String id) {
        return () -> id;
    }

    /**
     * 获取所有支持的网络组件类型
     *
     * @return 网络类型列表
     */
    static List<NetworkType> getAll() {
        return NetworkTypes.get();
    }

    /**
     * 根据ID查找NetworkType
     *
     * @param id 类型ID
     * @return 可能包含NetworkType的Optional
     */
    static Optional<NetworkType> lookup(String id) {
        return NetworkTypes.lookup(id);
    }

    /**
     * 获取类型唯一标识
     *
     * @return 类型ID
     */
    String getId();

    /**
     * 获取类型名称，默认返回ID
     *
     * @return 类型名称
     */
    default String getName() {
        return getId();
    }
} 