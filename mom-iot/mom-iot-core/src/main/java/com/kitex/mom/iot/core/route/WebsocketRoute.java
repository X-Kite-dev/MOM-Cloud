package com.kitex.mom.iot.core.route;

public interface WebsocketRoute extends Route {

    static Builder builder() {
        return DefaultWebsocketRoute.builder();
    }

    default String getPath() {
        return getAddress();
    }

    interface Builder {
        Builder group(String group);

        Builder path(String address);

        Builder description(String description);

        Builder example(String example);

        WebsocketRoute build();
    }
}
