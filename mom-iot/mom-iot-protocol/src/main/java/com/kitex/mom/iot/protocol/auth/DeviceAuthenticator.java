package com.kitex.mom.iot.protocol.auth;

import com.kitex.mom.iot.core.protocol.TransportType;

/**
 * 设备认证器接口
 * 负责设备连接认证
 */
public interface DeviceAuthenticator {

    /**
     * 获取支持的传输类型
     *
     * @return 传输类型
     */
    TransportType getTransportType();

    /**
     * 认证设备
     *
     * @param context 认证上下文
     * @return 认证结果
     */
    AuthenticationResult authenticate(AuthenticationContext context);

    /**
     * 认证上下文接口
     */
    interface AuthenticationContext {
        /**
         * 获取设备ID/客户端ID
         *
         * @return 设备ID
         */
        String getDeviceId();

        /**
         * 获取用户名，可能为null
         *
         * @return 用户名
         */
        String getUsername();

        /**
         * 获取密码，可能为null
         *
         * @return 密码
         */
        String getPassword();

        /**
         * 获取客户端地址
         *
         * @return 客户端地址
         */
        String getClientAddress();

        /**
         * 获取Headers信息
         *
         * @param key 键
         * @return 值
         */
        String getHeader(String key);
    }

    /**
     * 认证结果
     */
    class AuthenticationResult {
        /**
         * 是否认证成功
         */
        private final boolean success;

        /**
         * 认证失败原因
         */
        private final String message;

        /**
         * 设备ID
         */
        private final String deviceId;

        /**
         * 产品ID
         */
        private final String productId;

        private AuthenticationResult(boolean success, String message, String deviceId, String productId) {
            this.success = success;
            this.message = message;
            this.deviceId = deviceId;
            this.productId = productId;
        }

        public static AuthenticationResult success(String deviceId, String productId) {
            return new AuthenticationResult(true, "认证成功", deviceId, productId);
        }

        public static AuthenticationResult failure(String message) {
            return new AuthenticationResult(false, message, null, null);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public String getDeviceId() {
            return deviceId;
        }

        public String getProductId() {
            return productId;
        }
    }
} 