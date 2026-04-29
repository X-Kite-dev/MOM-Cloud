package com.kitex.mom.iot.gateway;

import java.util.HashMap;
import java.util.Map;

/**
 * 传输协议类，定义支持的传输协议
 */
public class Transport {

    /**
     * TCP传输协议
     */
    public static final Transport TCP = of("tcp");

    /**
     * UDP传输协议
     */
    public static final Transport UDP = of("udp");

    /**
     * MQTT传输协议
     */
    public static final Transport MQTT = of("mqtt");

    /**
     * HTTP传输协议
     */
    public static final Transport HTTP = of("http");
    /**
     * 所有注册的传输协议
     */
    private static final Map<String, Transport> ALL = new HashMap<>();

    static {
        // 注册默认传输协议
        register(TCP);
        register(UDP);
        register(MQTT);
        register(HTTP);
    }

    /**
     * 协议标识
     */
    private final String id;
    /**
     * 协议名称
     */
    private final String name;

    /**
     * 构造函数
     *
     * @param id   协议标识
     * @param name 协议名称
     */
    public Transport(String id, String name) {
        this.id = id;
        this.name = name;
    }

    /**
     * 根据标识创建传输协议
     *
     * @param id 协议标识
     * @return 传输协议
     */
    public static Transport of(String id) {
        return of(id, id);
    }

    /**
     * 根据标识和名称创建传输协议
     *
     * @param id   协议标识
     * @param name 协议名称
     * @return 传输协议
     */
    public static Transport of(String id, String name) {
        return new Transport(id, name);
    }

    /**
     * 注册传输协议
     *
     * @param transport 传输协议
     */
    public static void register(Transport transport) {
        ALL.put(transport.getId(), transport);
    }

    /**
     * 根据标识获取传输协议
     *
     * @param id 协议标识
     * @return 传输协议，如果不存在则创建新的
     */
    public static Transport get(String id) {
        return ALL.getOrDefault(id, of(id));
    }

    /**
     * 获取协议标识
     *
     * @return 协议标识
     */
    public String getId() {
        return id;
    }

    /**
     * 获取协议名称
     *
     * @return 协议名称
     */
    public String getName() {
        return name;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof Transport) {
            return ((Transport) obj).getId().equals(this.getId());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
} 