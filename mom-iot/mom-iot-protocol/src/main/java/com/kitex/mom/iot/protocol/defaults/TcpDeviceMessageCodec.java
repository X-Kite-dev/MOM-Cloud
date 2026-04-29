package com.kitex.mom.iot.protocol.defaults;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.mom.iot.core.codec.DeviceMessageCodec;
import com.kitex.mom.iot.core.message.DeviceMessage;
import com.kitex.mom.iot.core.message.codec.MessageDecodeContext;
import com.kitex.mom.iot.core.message.codec.TcpMessage;
import com.kitex.mom.iot.core.protocol.TransportType;
import com.kitex.mom.iot.protocol.BinaryMessageType;
import com.kitex.mom.iot.protocol.utils.ObjectMappers;
import io.netty.buffer.ByteBuf;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

@Slf4j
public class TcpDeviceMessageCodec implements DeviceMessageCodec {

    private final TransportType transport;

    private final ObjectMapper mapper;

    public TcpDeviceMessageCodec(TransportType transport) {
        this.transport = transport;
        this.mapper = ObjectMappers.JSON_MAPPER;
    }

    public TcpDeviceMessageCodec() {
        this(TransportType.TCP);
    }

    @Override
    public TransportType getSupportTransport() {
        return transport;
    }

    @Override
    public DeviceMessage decode(MessageDecodeContext context) {
        log.info("tcp decode -> {}", context.getMessage());

        TcpMessage message = (TcpMessage) context.getMessage();
        ByteBuf payload = message.getPayload();


        byte[] result = new byte[payload.readableBytes()];
        payload.getBytes(payload.readerIndex(), result);
        String payloadString = new String(result, StandardCharsets.UTF_8);

        log.info("获取的tcp数据-> {}", payloadString);
        return BinaryMessageType.read(payload);
    }
}
