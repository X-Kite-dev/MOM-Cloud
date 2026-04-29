package com.kitex.mom.iot.core.protocol;

import java.util.List;

public interface ProtocolSupports {

    ProtocolSupport getProtocol(String protocol);

    List<ProtocolSupport> getProtocols();
}
