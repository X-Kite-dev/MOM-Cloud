package com.kitex.mom.iot.core.codec;

import com.kitex.mom.iot.core.message.DeviceMessage;
import com.kitex.mom.iot.core.message.codec.MessageDecodeContext;

/**
 * 设备消息编码器,用于将消息对象编码为对应消息协议的消息
 */
public interface DeviceMessageEncoder {
    /**
     * 编码,将消息进行编码,用于发送到设备端.
     */


    DeviceMessage decode(MessageDecodeContext context);


}
