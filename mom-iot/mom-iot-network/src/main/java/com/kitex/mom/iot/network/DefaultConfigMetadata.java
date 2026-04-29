package com.kitex.mom.iot.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 默认配置元数据实现
 */
public class DefaultConfigMetadata implements ConfigMetadata {

    private final List<ConfigPropertyMetadata> properties = new ArrayList<>();

    /**
     * 添加配置属性
     *
     * @param property    属性名
     * @param name        显示名称
     * @param description 描述
     * @return this
     */
    public DefaultConfigMetadata add(String property, String name, String description) {
        properties.add(new DefaultConfigPropertyMetadata(property, name, description));
        return this;
    }

    /**
     * 添加配置属性
     *
     * @param property    属性名
     * @param name        显示名称
     * @param description 描述
     * @param type        数据类型
     * @return this
     */
    public DefaultConfigMetadata add(String property, String name, String description, String type) {
        properties.add(new DefaultConfigPropertyMetadata(property, name, description, type));
        return this;
    }

    @Override
    public List<ConfigPropertyMetadata> getProperties() {
        return Collections.unmodifiableList(properties);
    }

    /**
     * 默认配置属性元数据实现
     */
    private static class DefaultConfigPropertyMetadata implements ConfigPropertyMetadata {

        private final String property;
        private final String name;
        private final String description;
        private final String type;

        public DefaultConfigPropertyMetadata(String property, String name, String description) {
            this(property, name, description, "string");
        }

        public DefaultConfigPropertyMetadata(String property, String name, String description, String type) {
            this.property = property;
            this.name = name;
            this.description = description;
            this.type = type;
        }

        @Override
        public String getProperty() {
            return property;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public String getType() {
            return type;
        }
    }
} 