package com.kitex.mom.iot.core.device;

import lombok.*;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * 产品信息
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductInfo implements Serializable {
    private static final long serialVersionUID = -6849794470754667710L;

    /**
     * 设备ID
     */
    private String id;

    /**
     * 消息协议
     */
    private String protocol;

    /**
     * 元数据
     */
    private String metadata;

    /**
     * 其他配置
     */
    private Map<String, Object> configuration = new HashMap<>();

    /**
     * 版本号
     */
    private String version;

    public ProductInfo(String id, String protocol, String metadata) {
        this.id = id;
        this.protocol = protocol;
        this.metadata = metadata;
    }

    public ProductInfo addConfig(String key, Object value) {
        if (configuration == null) {
            configuration = new HashMap<>();
        }
        configuration.put(key, value);
        return this;
    }

    public ProductInfo addConfigs(Map<String, ?> configs) {
        if (configs == null) {
            return this;
        }
        configs.forEach(this::addConfig);
        return this;
    }
}
