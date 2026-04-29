package com.kitex.mom.iot.core.protocol;

/**
 * 设备协议提供商
 */
public interface ProtocolSupportProvider {
    /**
     * 创建协议
     *
     * @return
     */
    ProtocolSupport create();
}
