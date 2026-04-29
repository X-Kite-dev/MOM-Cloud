package com.kitex.mom.iot.protocol;

import com.kitex.mom.iot.core.protocol.ProtocolSupport;
import com.kitex.mom.iot.core.protocol.ProtocolSupportProvider;
import com.kitex.mom.iot.core.protocol.ProtocolSupports;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class DefaultProtocolSupports implements ProtocolSupports {

    private final Map<String, ProtocolSupport> protocols = new ConcurrentHashMap<>();

    public DefaultProtocolSupports register(ProtocolSupportProvider provider) {
        ProtocolSupport protocolSupport = provider.create();
        protocols.put(protocolSupport.getId(), protocolSupport);
        return this;
    }

    @Override
    public ProtocolSupport getProtocol(String protocol) {
        return protocols.get(protocol);
    }

    @Override
    public List<ProtocolSupport> getProtocols() {
        return protocols.values().stream().collect(Collectors.toList());
    }
}
