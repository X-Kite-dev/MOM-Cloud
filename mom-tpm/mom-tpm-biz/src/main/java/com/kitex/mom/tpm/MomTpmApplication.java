package com.kitex.mom.tpm;

import com.kitex.common.feign.annotation.EnableMomFeignClients;
import com.kitex.common.job.annotation.EnableMomXxlJob;
import com.kitex.common.security.annotation.EnableMomResourceServer;
import com.kitex.common.swagger.annotation.EnableOpenApi;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 项目启动类
 */
@EnableOpenApi("mom-tpm")
@EnableMomFeignClients
@EnableDiscoveryClient
@EnableMomResourceServer
@SpringBootApplication
@EnableMomXxlJob
//@EnableScheduling
public class MomTpmApplication {
    public static void main(String[] args) {
        SpringApplication.run(MomTpmApplication.class, args);
    }
}
