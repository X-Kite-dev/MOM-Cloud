package com.kitex.mom.iot.core.message.codec;

import com.kitex.mom.iot.core.message.DeviceMessage;

public interface MessageDecodeContext extends MessageCodecContext {


    EncodedMessage getMessage();


    default Void handleMessage(DeviceMessage message) {
        throw new UnsupportedOperationException("handleMessage not supported");
    }

}
