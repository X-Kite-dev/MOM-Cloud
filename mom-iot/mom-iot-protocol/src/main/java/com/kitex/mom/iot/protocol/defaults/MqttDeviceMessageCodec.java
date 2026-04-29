package com.kitex.mom.iot.protocol.defaults;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.mom.iot.core.codec.DeviceMessageCodec;
import com.kitex.mom.iot.core.message.DeviceMessage;
import com.kitex.mom.iot.core.message.codec.MessageDecodeContext;
import com.kitex.mom.iot.core.message.codec.SimpleMqttMessage;
import com.kitex.mom.iot.core.protocol.TransportType;
import com.kitex.mom.iot.protocol.TopicMessageCodec;
import com.kitex.mom.iot.protocol.utils.ObjectMappers;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

@Slf4j
public class MqttDeviceMessageCodec implements DeviceMessageCodec {

    private final TransportType transport;

    private final ObjectMapper mapper;

    public MqttDeviceMessageCodec(TransportType transport) {
        this.transport = transport;
        this.mapper = ObjectMappers.JSON_MAPPER;
    }

    public MqttDeviceMessageCodec() {
        this(TransportType.MQTT);
    }


    @Override
    public TransportType getSupportTransport() {
        return transport;
    }

    /**
     * 解码器
     *
     * @param context
     * @return
     */
    @Override
    public DeviceMessage decode(MessageDecodeContext context) {
        log.info("mqtt decode -> {}", context.getMessage());


        SimpleMqttMessage message = (SimpleMqttMessage) context.getMessage();

        byte[] result = new byte[message.getPayload().readableBytes()];
        message.getPayload().getBytes(message.getPayload().readerIndex(), result);

        String payloadString = new String(result, StandardCharsets.UTF_8);

        DeviceMessage decode = TopicMessageCodec.decode(mapper, TopicMessageCodec.removeProductPath(message.getTopic()), payloadString);

        return decode;
    }
}
