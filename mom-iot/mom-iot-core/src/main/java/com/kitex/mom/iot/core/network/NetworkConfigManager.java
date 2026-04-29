package com.kitex.mom.iot.core.network;

import java.util.Collections;
import java.util.List;

/**
 * 网络组件配置管理器
 */
public interface NetworkConfigManager {

    /**
     * 获取全部的网络配置
     *
     * @return 配置信息
     * @since 2.0
     */
    default List<NetworkProperties> getAllConfigs() {
        return Collections.emptyList();
    }

    NetworkProperties getConfig(NetworkType networkType, String id);

}
