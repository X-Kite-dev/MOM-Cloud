package com.kitex.mom.iot.protocol;

import cn.hutool.core.bean.BeanUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.mom.iot.core.message.DeviceMessage;
import com.kitex.mom.iot.core.message.property.ReadPropertyMessage;
import com.kitex.mom.iot.core.message.property.ReadPropertyMessageReply;
import com.kitex.mom.iot.core.message.property.ReportPropertyMessage;
import com.kitex.mom.iot.core.route.MqttRoute;
import com.kitex.mom.iot.protocol.utils.TopicUtils;
import lombok.SneakyThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.Function;

public enum TopicMessageCodec {


    reportProperty("/*/properties/report", ReportPropertyMessage.class,
            route -> route
                    .upstream(true)
                    .downstream(false)
                    .group("属性上报")
                    .description("上报物模型属性数据")
                    .example("{\"properties\":{\"属性ID\":\"属性值\"}}")),

    readProperty("/*/properties/read",
            ReadPropertyMessage.class,
            route -> route
                    .upstream(false)
                    .downstream(true)
                    .group("读取属性")
                    .description("平台下发读取物模型属性数据指令")
                    .example("{\"messageId\":\"消息ID,回复时需要一致.\",\"properties\":[\"属性ID\"]}")),

    readPropertyReply("/*/properties/read/reply",
            ReadPropertyMessageReply.class,
            route -> route
                    .upstream(true)
                    .downstream(false)
                    .group("读取属性")
                    .description("对平台下发的读取属性指令进行响应")
                    .example("{\"messageId\":\"消息ID,与读取指令中的ID一致.\",\"properties\":{\"属性ID\":\"属性值\"}}")),


    ;

    private final String[] pattern;
    private final MqttRoute route;
    private final Class<? extends DeviceMessage> type;

    TopicMessageCodec(String topic,
                      Class<? extends DeviceMessage> type,
                      Function<MqttRoute.Builder, MqttRoute.Builder> routeCustom) {
        this.pattern = topic.split("/");
        this.type = type;
        this.route = routeCustom.apply(toRoute()).build();
    }

    TopicMessageCodec(String topic,
                      Class<? extends DeviceMessage> type) {
        this.pattern = topic.split("/");
        this.type = type;
        this.route = null;
    }

    public static String[] removeProductPath(String topic) {
        if (!topic.startsWith("/")) {
            topic = "/" + topic;
        }
        String[] topicArr = topic.split("/");
        String[] topics = Arrays.copyOfRange(topicArr, 1, topicArr.length);
        topics[0] = "";
        return topics;
    }

    /**
     * 解码
     *
     * @param mapper
     * @param topics
     * @param payload
     * @return
     */
    public static DeviceMessage decode(ObjectMapper mapper, String[] topics, String payload) {
        Optional<TopicMessageCodec> topicMessageCodec1 = fromTopic(topics);
        TopicMessageCodec topicMessageCodec = topicMessageCodec1.orElseThrow(() -> new RuntimeException("topic not found"));
        return topicMessageCodec.doDecode(mapper, topics, payload);
    }

    static Optional<TopicMessageCodec> fromTopic(String[] topic) {
        for (TopicMessageCodec value : values()) {
            if (TopicUtils.match(value.pattern, topic)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    public MqttRoute getRoute() {
        return route;
    }

    /**
     * 解码
     *
     * @param mapper
     * @param topic
     * @param payload
     * @return
     */
    @SneakyThrows
    DeviceMessage doDecode(ObjectMapper mapper, String[] topic, String payload) {
        DeviceMessage message = mapper.readValue(payload, type);
        BeanUtil.copyProperties(Collections.singletonMap("deviceId", topic[1]), message);
        return message;
    }

    @SneakyThrows
    private MqttRoute.Builder toRoute() {
        String[] topics = new String[pattern.length];
        System.arraycopy(pattern, 0, topics, 0, pattern.length);
        topics[0] = "{productId:产品ID}";
        topics[1] = "{deviceId:设备ID}";
        transMqttTopic(topics);
        StringJoiner joiner = new StringJoiner("/", "/", "");
        for (String topic : topics) {
            joiner.add(topic);
        }
        return MqttRoute
                .builder(joiner.toString())
                .qos(1);
    }

    protected void transMqttTopic(String[] topic) {

    }
}
