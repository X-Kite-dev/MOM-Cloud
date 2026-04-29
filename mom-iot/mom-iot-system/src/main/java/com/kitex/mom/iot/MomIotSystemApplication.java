package com.kitex.mom.iot;

import com.kitex.common.feign.annotation.EnableMomFeignClients;
import com.kitex.common.security.annotation.EnableMomResourceServer;
import com.kitex.common.swagger.annotation.EnableOpenApi;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * IoT系统启动类
 *
 * @author system
 * @date 2025-03-12
 */
@EnableOpenApi("mom-iot")
@EnableMomFeignClients
@EnableDiscoveryClient
@EnableMomResourceServer
@SpringBootApplication
public class MomIotSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(MomIotSystemApplication.class, args);
    }
} 