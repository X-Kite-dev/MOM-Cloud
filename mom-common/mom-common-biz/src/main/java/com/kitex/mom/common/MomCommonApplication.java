package com.kitex.mom.common;

import com.kitex.common.feign.annotation.EnableMomFeignClients;
import com.kitex.common.security.annotation.EnableMomResourceServer;
import com.kitex.common.swagger.annotation.EnableOpenApi;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@EnableOpenApi("mom-common")
@EnableMomFeignClients
@EnableDiscoveryClient
@EnableMomResourceServer
@SpringBootApplication
public class MomCommonApplication {
    public static void main(String[] args) {
        SpringApplication.run(MomCommonApplication.class, args);
    }
}
