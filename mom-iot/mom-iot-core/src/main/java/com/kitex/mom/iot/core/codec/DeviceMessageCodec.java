package com.kitex.mom.iot.core.codec;


import com.kitex.mom.iot.core.protocol.TransportType;

/**
 * 设备消息转换器,用于对不同协议的消息进行转换
 */
public interface DeviceMessageCodec extends DeviceMessageEncoder, DeviceMessageDecoder {

    /**
     * @return 返回支持的传输协议
     */
    TransportType getSupportTransport();


}
