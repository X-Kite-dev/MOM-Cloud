package com.kitex.mom.iot.core.message.codec;

import io.netty.buffer.ByteBuf;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;


@AllArgsConstructor
@RequiredArgsConstructor
@Data
public class TcpMessage implements EncodedMessage {


    private ByteBuf payload;

}
