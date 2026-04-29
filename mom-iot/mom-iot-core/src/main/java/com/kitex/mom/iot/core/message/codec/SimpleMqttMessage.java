package com.kitex.mom.iot.core.message.codec;

import io.netty.buffer.ByteBuf;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

@Builder
public class SimpleMqttMessage implements EncodedMessage {

    /**
     * 主题
     */
    @Getter
    private String topic;


    /**
     * 消息内容
     */
    private ByteBuf payload;

    /**
     * Qos
     */
    private int qosLevel;


    /**
     * 消息id
     */
    private int messageId;


    /**
     * 客户端id
     */
    private String clientId;


    public static SimpleMqttMessage create(String topic, ByteBuf payload) {
        return builder()
                .topic(topic)
                .payload(payload)
                .build();
    }

    public static SimpleMqttMessage create(String topic, ByteBuf payload, int qos) {
        return builder()
                .topic(topic)
                .payload(payload)
                .qosLevel(qos)
                .build();
    }


    @NotNull
    @Override
    public ByteBuf getPayload() {
        return this.payload;
    }

}
