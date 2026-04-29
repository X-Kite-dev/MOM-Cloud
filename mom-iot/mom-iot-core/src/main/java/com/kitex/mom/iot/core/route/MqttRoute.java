package com.kitex.mom.iot.core.route;

public interface MqttRoute extends Route {

    static Builder builder(String topic) {
        return DefaultMqttRoute
                .builder()
                .topic(topic);
    }

    String getTopic();

    boolean isUpstream();

    boolean isDownstream();

    default int getQos() {
        return 0;
    }

    @Override
    default String getAddress() {
        return getTopic();
    }

    interface Builder {

        Builder group(String group);

        Builder topic(String topic);

        Builder qos(int qos);

        Builder downstream(boolean downstream);

        Builder upstream(boolean downstream);

        Builder description(String description);

        Builder example(String example);

        MqttRoute build();
    }
}
