package com.kitex.mom.iot.protocol.binary;

import com.kitex.mom.iot.core.message.DeviceMessage;
import com.kitex.mom.iot.protocol.BinaryMessageType;
import io.netty.buffer.ByteBuf;

public interface BinaryMessage<T extends DeviceMessage> {

    BinaryMessageType getType();

    void read(ByteBuf buf);

    void write(ByteBuf buf);

    T getMessage();

    void setMessage(T message);

}
