package com.kitex.mom.iot.gateway;

import java.util.HashMap;
import java.util.Map;

/**
 * 设备网关消息接口
 */
public interface Message {

    /**
     * 创建一个简单的消息实现
     *
     * @param deviceId  设备ID
     * @param messageId 消息ID
     * @param type      消息类型
     * @param payload   消息负载
     * @return 消息实例
     */
    static Message of(String deviceId, String messageId, String type, Object payload) {
        return new SimpleMessage(deviceId, messageId, type, payload);
    }

    /**
     * 获取消息ID
     *
     * @return 消息ID
     */
    String getMessageId();

    /**
     * 获取设备ID
     *
     * @return 设备ID
     */
    String getDeviceId();

    /**
     * 获取消息创建时间
     *
     * @return 消息创建时间
     */
    long getTimestamp();

    /**
     * 获取消息类型
     *
     * @return 消息类型
     */
    String getType();

    /**
     * 获取消息负载
     *
     * @return 消息负载
     */
    Object getPayload();

    /**
     * 获取消息头
     *
     * @return 消息头
     */
    Map<String, Object> getHeaders();

    /**
     * 简单消息实现
     */
    class SimpleMessage implements Message {
        private final String deviceId;
        private final String messageId;
        private final String type;
        private final Object payload;
        private final long timestamp;
        private final Map<String, Object> headers = new HashMap<>();

        /**
         * 构造函数
         *
         * @param deviceId  设备ID
         * @param messageId 消息ID
         * @param type      消息类型
         * @param payload   消息负载
         */
        public SimpleMessage(String deviceId, String messageId, String type, Object payload) {
            this.deviceId = deviceId;
            this.messageId = messageId;
            this.type = type;
            this.payload = payload;
            this.timestamp = System.currentTimeMillis();
        }

        @Override
        public String getMessageId() {
            return messageId;
        }

        @Override
        public String getDeviceId() {
            return deviceId;
        }

        @Override
        public long getTimestamp() {
            return timestamp;
        }

        @Override
        public String getType() {
            return type;
        }

        @Override
        public Object getPayload() {
            return payload;
        }

        @Override
        public Map<String, Object> getHeaders() {
            return headers;
        }
    }
} 