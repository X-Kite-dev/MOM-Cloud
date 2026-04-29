package com.kitex.mom.iot.network;

import java.util.List;

/**
 * 配置元数据接口
 */
public interface ConfigMetadata {

    /**
     * 获取配置属性列表
     *
     * @return 配置属性列表
     */
    List<ConfigPropertyMetadata> getProperties();

    /**
     * 配置属性元数据接口
     */
    interface ConfigPropertyMetadata {

        /**
         * 获取属性名
         *
         * @return 属性名
         */
        String getProperty();

        /**
         * 获取显示名称
         *
         * @return 显示名称
         */
        String getName();

        /**
         * 获取描述
         *
         * @return 描述
         */
        String getDescription();

        /**
         * 获取数据类型
         *
         * @return 数据类型
         */
        String getType();
    }
} 