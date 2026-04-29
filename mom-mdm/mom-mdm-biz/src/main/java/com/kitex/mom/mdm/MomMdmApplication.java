package com.kitex.mom.mdm;

import com.kitex.common.feign.annotation.EnableMomFeignClients;
import com.kitex.common.job.annotation.EnableMomXxlJob;
import com.kitex.common.security.annotation.EnableMomResourceServer;
import com.kitex.common.swagger.annotation.EnableOpenApi;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@EnableOpenApi("mom-mdm")
@EnableMomFeignClients
@EnableDiscoveryClient
@EnableMomResourceServer
@SpringBootApplication
@EnableMomXxlJob
@EnableCaching
public class MomMdmApplication {

    public static void main(String[] args) {
        SpringApplication.run(MomMdmApplication.class, args);
    }

}