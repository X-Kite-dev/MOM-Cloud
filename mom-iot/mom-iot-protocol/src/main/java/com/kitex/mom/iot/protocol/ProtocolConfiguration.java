package com.kitex.mom.iot.protocol;


import com.kitex.mom.iot.core.protocol.ProtocolSupportProvider;
import com.kitex.mom.iot.core.protocol.ProtocolSupports;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class ProtocolConfiguration {


    @Bean
    public ProtocolSupports protocolSupports(List<ProtocolSupportProvider> providers) {
        DefaultProtocolSupports protocolSupports = new DefaultProtocolSupports();
        providers.forEach(protocolSupports::register);
        return protocolSupports;

    }

}
