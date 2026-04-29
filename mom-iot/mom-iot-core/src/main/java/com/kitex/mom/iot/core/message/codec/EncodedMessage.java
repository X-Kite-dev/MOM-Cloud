package com.kitex.mom.iot.core.message.codec;

import io.netty.buffer.ByteBuf;
import jakarta.annotation.Nonnull;

/**
 * 已编码的消息,通常为来自设备或者发向设备的原始报文.
 */
public interface EncodedMessage {

    /**
     * 获取原始报文
     *
     * @return ByteBuf
     */
    @Nonnull
    ByteBuf getPayload();


    default ByteBuf payloadAsString() {
        return getPayload();
    }

}
